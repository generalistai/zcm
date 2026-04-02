#include <iostream>
#include <cstdlib>
#include <cstring>
#include <cassert>
#include <unistd.h>
#include <cinttypes>
#include <regex>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <queue>
#include <vector>
#include <signal.h>
#include <string>
#include <map>

#include <errno.h>
#include <time.h>
#include <getopt.h>
#include <sys/socket.h>

#include "zcm/zcm-cpp.hpp"
#include "zcm/util/debug.h"
#include "zcm/zcm_coretypes.h"

#include "util/TranscoderPluginDb.hpp"

#include "util/FileUtil.hpp"
#include "util/TimeUtil.hpp"
#include "util/Types.hpp"

using namespace std;

#include "platform.hpp"

static atomic_int done {0};
static atomic_int got_sighup {0};

namespace
{
constexpr char CONTROL_MAGIC[] = {'L', 'G', 'C', 'P'};
constexpr char CONTROL_REQUEST_CHANNEL[] = "logger_control_v1";
constexpr char BARRIER_MARKER_CHANNEL[] = "logger_cycle_barrier_v1";
constexpr uint16_t CONTROL_VERSION = 1;
constexpr uint16_t MSG_READY = 1;
constexpr uint16_t MSG_CYCLE_REQUEST = 2;
constexpr uint16_t MSG_CYCLE_COMPLETE = 3;
constexpr uint16_t MSG_CYCLE_ERROR = 4;
constexpr uint32_t ERR_BUSY_WITH_OTHER_CYCLE = 1;
constexpr uint32_t ERR_ROTATE_CLOSE_FAILED = 2;
constexpr uint32_t ERR_ROTATE_OPEN_FAILED = 3;
constexpr uint32_t ERR_CONTROL_PROTOCOL_INTERNAL = 4;
constexpr int64_t INTERNAL_BARRIER_EVENTNUM = -1;
constexpr size_t CONTROL_HEADER_SIZE = 12;
constexpr size_t READY_FRAME_SIZE = CONTROL_HEADER_SIZE + 8;
constexpr size_t CYCLE_REQUEST_FRAME_SIZE = CONTROL_HEADER_SIZE + 8 + 8 + 8 + 8;

struct CycleRequestFrame
{
    uint64_t control_session_id = 0;
    uint64_t cycle_seq = 0;
    int64_t cycle_reason = 0;
    int64_t request_robot_time_us = 0;
};

void append_u16_be(vector<uint8_t>& frame, uint16_t value)
{
    frame.push_back(static_cast<uint8_t>((value >> 8) & 0xff));
    frame.push_back(static_cast<uint8_t>(value & 0xff));
}

void append_u32_be(vector<uint8_t>& frame, uint32_t value)
{
    frame.push_back(static_cast<uint8_t>((value >> 24) & 0xff));
    frame.push_back(static_cast<uint8_t>((value >> 16) & 0xff));
    frame.push_back(static_cast<uint8_t>((value >> 8) & 0xff));
    frame.push_back(static_cast<uint8_t>(value & 0xff));
}

void append_u64_be(vector<uint8_t>& frame, uint64_t value)
{
    for (int shift = 56; shift >= 0; shift -= 8)
        frame.push_back(static_cast<uint8_t>((value >> shift) & 0xff));
}

uint16_t read_u16_be(const uint8_t* data)
{
    return (static_cast<uint16_t>(data[0]) << 8) |
           static_cast<uint16_t>(data[1]);
}

uint32_t read_u32_be(const uint8_t* data)
{
    return (static_cast<uint32_t>(data[0]) << 24) |
           (static_cast<uint32_t>(data[1]) << 16) |
           (static_cast<uint32_t>(data[2]) << 8) |
           static_cast<uint32_t>(data[3]);
}

uint64_t read_u64_be(const uint8_t* data)
{
    uint64_t value = 0;
    for (int i = 0; i < 8; ++i)
        value = (value << 8) | static_cast<uint64_t>(data[i]);
    return value;
}

int64_t read_i64_be(const uint8_t* data)
{
    return static_cast<int64_t>(read_u64_be(data));
}

bool parseCycleRequestFrame(const uint8_t* data, size_t size, CycleRequestFrame* out)
{
    if (size != CYCLE_REQUEST_FRAME_SIZE)
        return false;
    if (memcmp(data, CONTROL_MAGIC, sizeof(CONTROL_MAGIC)) != 0)
        return false;
    if (read_u16_be(data + 4) != CONTROL_VERSION)
        return false;
    if (read_u16_be(data + 6) != MSG_CYCLE_REQUEST)
        return false;
    if (read_u32_be(data + 8) != size)
        return false;

    out->control_session_id = read_u64_be(data + CONTROL_HEADER_SIZE);
    out->cycle_seq = read_u64_be(data + CONTROL_HEADER_SIZE + 8);
    out->cycle_reason = read_i64_be(data + CONTROL_HEADER_SIZE + 16);
    out->request_robot_time_us = read_i64_be(data + CONTROL_HEADER_SIZE + 24);
    return true;
}
}

struct Args
{
    struct Shard
    {
        string zcmurl = "";
        vector<string> channels;
        int queue_size = 0;

        Shard(const string& zcmurl, int queue_size) :
            zcmurl(zcmurl), queue_size(queue_size)
        {}
    };

    vector<Shard> shards;

    double auto_split_mb      = 0.0;
    bool   force_overwrite    = false;
    bool   auto_increment     = false;
    bool   use_strftime       = false;
    bool   quiet              = false;
    int    rotate             = -1;
    int    fflush_interval_ms = 100;
    i64    max_target_memory  = 0;
    string plugin_path        = "";
    bool   debug              = false;
    int    control_fd         = -1;
    uint64_t control_session_id = 0;
    bool   control_enabled    = false;
    map<string, string> channel_renames;


    string input_fname;

    bool parse(int argc, char *argv[])
    {
        // set some defaults
        const char *optstring = "hu:c:z:b:fir:s:ql:m:p:n:dR:";
        enum LongOnlyOption {
            CONTROL_FD = 1000,
            CONTROL_SESSION_ID,
        };
        struct option long_opts[] = {
            { "help",              no_argument,       0, 'h' },
            { "zcm-url",           required_argument, 0, 'u' },
            { "channel",           required_argument, 0, 'c' },
            { "queue-size",        required_argument, 0, 'z' },
            { "split-mb",          required_argument, 0, 'b' },
            { "force",             no_argument,       0, 'f' },
            { "increment",         no_argument,       0, 'i' },
            { "rotate",            required_argument, 0, 'r' },
            { "strftime",          required_argument, 0, 's' },
            { "quiet",             no_argument,       0, 'q' },
            { "flush-interval",    required_argument, 0, 'l' },
            { "max-target-memory", required_argument, 0, 'm' },
            { "plugin-path",       required_argument, 0, 'p' },
            { "name",              required_argument, 0, 'n' },
            { "debug",             no_argument,       0, 'd' },
            { "rename-channel",    required_argument, 0, 'R' },
            { "control-fd",        required_argument, 0, CONTROL_FD },
            { "control-session-id", required_argument, 0, CONTROL_SESSION_ID },

            { 0, 0, 0, 0 }
        };

        auto launchRenamed = [&](int nameInd, bool verbose = false) {
            assert(nameInd > 1);

            int    newArgc   = argc - 2;
            char** newArgv   = new char*[newArgc + 1];
            newArgv[newArgc] = nullptr;

            newArgv[0] = argv[nameInd];
            for (int j = 1, i = 1; i < argc; ++i) {
                if (i == nameInd || i == nameInd - 1) continue;
                assert(j < newArgc);
                newArgv[j] = argv[i];
                ++j;
            }

            if (verbose) {
                for (int j = 0; j < newArgc; ++j) cout << newArgv[j] << " ";
                cout << endl;
            }

            return execvp(argv[0], newArgv);
        };

        int nameInd = -1;
        bool saw_control_fd = false;
        bool saw_control_session_id = false;

        int c;
        while ((c = getopt_long (argc, argv, optstring, long_opts, 0)) >= 0) {
            switch (c) {
                case 'u':
                    shards.emplace_back(optarg, 0);
                    break;
                case 'c':
                    if (shards.empty()) shards.emplace_back("", 0);
                    shards.back().channels.push_back(optarg);
                    break;
                case 'z':
                    if (shards.empty()) shards.emplace_back("", 0);
                    shards.back().queue_size = atoi(optarg);
                    if (shards.back().queue_size == 0) {
                        cerr << "Please specify a valid queue size greater than 0" << endl;
                        return false;
                    }
                    break;
                case 'b':
                    auto_split_mb = strtod(optarg, NULL);
                    if (auto_split_mb <= 0) {
                        cerr << "Please specify an auto split size greater than 0 MB" << endl;
                        return false;
                    }
                    break;
                case 'f':
                    force_overwrite = 1;
                    break;
                case 'i':
                    auto_increment = true;
                    break;
                case 'r': {
                    char* eptr = NULL;
                    rotate = strtol(optarg, &eptr, 10);
                    if (*eptr) {
                        cerr << "Please specify a valid rotate maximum" << endl;
                        return false;
                    }
                } break;
                case 's':
                    use_strftime = true;
                    break;
                case 'q':
                    quiet = true;
                    break;
                case 'l':
                    fflush_interval_ms = atol(optarg);
                    if (fflush_interval_ms <= 0) {
                        cerr << "Please specify a flush interval greater than 0 ms" << endl;
                        return false;
                    }
                    break;
                case 'm':
                    max_target_memory = atoll(optarg);
                    break;
                case 'p':
                    plugin_path = string(optarg);
                    break;
                case 'n':
                    nameInd = optind - 1;
                    break;
                case 'd':
                    debug = true;
                    break;
                case 'R': {
                    string rename_arg(optarg);
                    size_t delim_pos = rename_arg.find('=');
                    if (delim_pos == string::npos) {
                        cerr << "Invalid rename format. Use --rename-channel OLD_NAME=NEW_NAME" << endl;
                        return false;
                    }
                    string old_name = rename_arg.substr(0, delim_pos);
                    string new_name = rename_arg.substr(delim_pos + 1);
                    if (old_name.empty() || new_name.empty()) {
                        cerr << "Both old and new channel names must be provided" << endl;
                        return false;
                    }
                    channel_renames[old_name] = new_name;
                } break;
                case CONTROL_FD: {
                    char* eptr = NULL;
                    long parsed_value = strtol(optarg, &eptr, 10);
                    if (*eptr || parsed_value < 0) {
                        cerr << "Please specify a valid non-negative control fd" << endl;
                        return false;
                    }
                    control_fd = static_cast<int>(parsed_value);
                    saw_control_fd = true;
                } break;
                case CONTROL_SESSION_ID: {
                    char* eptr = NULL;
                    unsigned long long parsed_value = strtoull(optarg, &eptr, 10);
                    if (*eptr) {
                        cerr << "Please specify a valid control session id" << endl;
                        return false;
                    }
                    control_session_id = static_cast<uint64_t>(parsed_value);
                    saw_control_session_id = true;
                } break;

                case 'h': default: usage(); return false;
            };
        }

        if (nameInd != -1) {
            launchRenamed(nameInd, true);
            cerr << "Failed to relaunch with the custom process name" << endl;
            return false;
        }

        if (optind == argc) {
            input_fname = "zcmlog-%Y-%m-%d";
            auto_increment = true;
            use_strftime = true;
        } else if (optind == argc - 1) {
            input_fname = argv[optind];
        } else if (optind < argc-1) {
            return false;
        }

        if (shards.empty()) shards.emplace_back("", 0);
        for (auto& s : shards) {
            if (s.channels.empty()) {
                s.channels.push_back(".*");
            }
        }

        if (auto_split_mb > 0 && !(auto_increment || (rotate > 0))) {
            cerr << "ERROR.  --split-mb requires either --increment or --rotate" << endl;
            return false;
        }

        if (rotate > 0 && auto_increment) {
            cerr << "ERROR.  --increment and --rotate can't both be used." << endl
                 << "Note that if you don't want --increment, you must" << endl
                 << "specify a log filename." << endl;
            return false;
        }

        if (saw_control_fd != saw_control_session_id) {
            cerr << "ERROR.  --control-fd and --control-session-id must be provided together." << endl;
            return false;
        }
        control_enabled = saw_control_fd;

        return true;
    }

    void usage()
    {
        cout << "usage: zcm-logger [options] [FILE]" << endl
             << endl
             << "    ZCM message logging utility. Subscribes to traffic on one or more zcm" << endl
             << "    transports, and records all messages received on to FILE. If FILE is not" << endl
             << "    specified, then a filename is automatically chosen." << endl
             << endl
             << "Options:" << endl
             << endl
             << "  -u, --zcm-url=URL          Log messages on the specified ZCM URL" << endl
             << "                             Can specify this argument multiple times" << endl
             << "                             If no -c is specified for this -u, subscribe to \".*\"" << endl
             << "  -c, --channel=CHAN         Channel string to pass to zcm_subscribe." << endl
             << "                             Can provide multiple times."<< endl
             << "                             Every -c is subscribed to on the prior specified -u url" << endl
             << "                             If no -u url has been specified, -c will apply to the " << endl
             << "                             ZCM_DEFAULT_URL." << endl
             << "                             Inverting channel selection is possible through regex" << endl
             << "                             For example: -c \"^(?!(EXAMPLE)$).*$\" will subscribe" << endl
             << "                             to everything except \"EXAMPLE\"" << endl
             << "  -z, --queue-size=MSGS      Size of zcm send and receive queues in number of messages." << endl
             << "                             Can provide multiple times." << endl
             << "                             Applies to prior -u url." << endl
             << "  -l, --flush-interval=MS    Flush the log file to disk every MS milliseconds." << endl
             << "                             (default: 100)" << endl
             << "  -f, --force                Overwrite existing files" << endl
             << "  -h, --help                 Shows this help text and exits" << endl
             << "  -i, --increment            Automatically append a suffix to FILE" << endl
             << "                             such that the resulting filename does not" << endl
             << "                             already exist.  This option precludes -f and" << endl
             << "                             --rotate" << endl
             << "  -m, --max-unwritten-mb=SZ  Maximum size of received but unwritten" << endl
             << "                             messages to store in memory before dropping" << endl
             << "                             messages.  (default: 100 MB)" << endl
             << "  -r, --rotate=NUM           When creating a new log file, rename existing files" << endl
             << "                             out of the way and always write to FILE.0.  If" << endl
             << "                             FILE.0 already exists, it is renamed to FILE.1.  If" << endl
             << "                             FILE.1 exists, it is renamed to FILE.2, etc.  If" << endl
             << "                             FILE.NUM exists, then it is deleted.  This option" << endl
             << "                             precludes -i." << endl
             << "  -b, --split-mb=N           Automatically start writing to a new log" << endl
             << "                             file once the log file exceeds N MB in size" << endl
             << "                             (can be fractional).  This option requires -i" << endl
             << "                             or --rotate." << endl
             << "  -q, --quiet                Suppress normal output and only report errors." << endl
             << "  -s, --strftime             Format FILE with strftime." << endl
             << "  -m, --max-target-memory    Attempt to limit the total buffer usage to this" << endl
             << "                             amount of memory. If specified, ensure that this" << endl
             << "                             number is at least as large as the maximum message" << endl
             << "                             size you expect to receive. This argument is" << endl
             << "                             specified in bytes. Suffixes are not yet supported." << endl
             << "                             This argument is independent from --queue-size and total" << endl
             << "                             program memory usage will be closer to the sum of the size" << endl
             << "                             of all queues + max-target-memory" << endl
             << "  -p, --plugin-path=path     Path to shared library containing transcoder plugins" << endl
             << "  -n, --name                 Name this process a custom process name for htop." << endl
             << "  -R, --rename-channel OLD_NAME=NEW_NAME" << endl
             << "                             Rename a channel from OLD_NAME to NEW_NAME." << endl
             << "                             This option can be used multiple times." << endl
             << "                             This is helpful for systems where replaying a log can cause" << endl
             << "                             problems during replay, like when using ZCM to launch processes" << endl
             << "                             with a program like Procman." << endl
             << "      --control-fd=FD        Write machine-readable control replies to FD." << endl
             << "      --control-session-id=N Identify the active control session for replies." << endl
             << endl
             << "Rotating / splitting log files" << endl
             << "==============================" << endl
             << "    For long-term logging, zcm-logger can rotate through a fixed number of" << endl
             << "    log files, moving to a new log file as existing files reach a maximum size." << endl
             << "    To do this, use --rotate and --split-mb.  For example:" << endl
             << endl
             << "        # Rotate through logfile.0, logfile.1, ... logfile.4" << endl
             << "        zcm-logger --rotate=5 --split-mb=2 logfile" << endl
             << endl
             << "    Moving to a new file happens either when the current log file size exceeds" << endl
             << "    the limit specified by --split-mb, or when zcm-logger receives a SIGHUP." << endl
             << endl << endl;
    }
};

static zcm::LogEvent* cloneLogEvent(const zcm::LogEvent* evt)
{
    zcm::LogEvent* ret = new zcm::LogEvent;
    ret->eventnum  = evt->eventnum;
    ret->timestamp = evt->timestamp;
    ret->channel   = evt->channel;
    ret->datalen   = evt->datalen;
    ret->data      = new uint8_t[evt->datalen];
    memcpy(ret->data, evt->data, evt->datalen * sizeof(uint8_t));
    return ret;
}

struct Logger
{
    Args args;

    string filename;
    string fname_prefix;

    zcm::LogFile* log = nullptr;

    int next_increment_num          = 0;

    // variables for inverted matching (e.g., logging all but some channels)
    vector<regex> invert_regex;

    // these members controlled by writing
    size_t nevents                  = 0;
    size_t logsize                  = 0;
    size_t events_since_last_report = 0;
    u64    last_report_time         = 0;
    size_t last_report_logsize      = 0;
    u64    time0                    = TimeUtil::utime();
    u64    last_fflush_time         = 0;

    size_t dropped_packets_count    = 0;
    u64    last_drop_report_utime   = 0;
    size_t last_drop_report_count   = 0;

    int    num_splits               = 0;

    i64    totalMemoryUsage         = 0;

    mutex lk;
    condition_variable newEventCond;

    queue<zcm::LogEvent*> q;
    bool pending_cycle_valid = false;
    uint64_t pending_cycle_session_id = 0;
    uint64_t pending_cycle_seq = 0;
    bool completed_cycle_valid = false;
    uint64_t completed_cycle_session_id = 0;
    uint64_t completed_cycle_seq = 0;
    string completed_cycle_old_file;
    string completed_cycle_new_file;

    TranscoderPluginDb* pluginDb = nullptr;

    vector<vector<zcm::TranscoderPlugin*>> shard_plugins;

    Logger() {}

    ~Logger()
    {
        if (pluginDb) { delete pluginDb; pluginDb = nullptr; }
        for (auto& s : shard_plugins) {
            for (auto& p : s) {
                delete p;
            }
        }

        if (log) { log->close(); delete log; }

        while (!q.empty()) {
            delete[] q.front()->data;
            delete q.front();
            q.pop();
        }
    }

    bool init(int argc, char *argv[])
    {
        if (!args.parse(argc, argv))
            return false;

        if (!openLogfile())
            return false;

        if (!sendReady())
            return false;

        shard_plugins.resize(args.shards.size());

        // Load plugins from path if specified
        assert(pluginDb == nullptr);
        if (args.plugin_path != "") {
            pluginDb = new TranscoderPluginDb(args.plugin_path, args.debug);
            auto dbPluginsMeta = pluginDb->getPluginMeta();
            if (dbPluginsMeta.empty()) {
                cerr << "Couldn't find any plugins. Aborting." << endl;
                return false;
            }
            if (shard_plugins.size() > 1) {
                cerr << "You are using transcoder plugins with multiple shards." << endl
                     << "Reminder that each shard will get its own clone of each" << endl
                     << "transcoder plugin and each transcoder plugin will run " << endl
                     << "in that shards threads. " << endl
                     << "See comments in TranscoderPlugin.hpp for more information" << endl;
            }
            for (auto pmeta : dbPluginsMeta) {
                for (size_t i = 0; i < shard_plugins.size(); ++i) {
                    shard_plugins[i].push_back(pmeta.makeTranscoderPlugin());
                }
                if (args.debug) cout << "Loaded plugin: " << pmeta.className << endl;
            }
        }

        if (args.debug) return true;

        return true;
    }

    bool sendControlFrame(const vector<uint8_t>& frame)
    {
        if (!args.control_enabled)
            return true;

        ssize_t bytes_sent = send(args.control_fd, frame.data(), frame.size(), MSG_NOSIGNAL);
        if (bytes_sent < 0) {
            perror("Error sending control frame");
            return false;
        }
        if (static_cast<size_t>(bytes_sent) != frame.size()) {
            cerr << "Error sending control frame: partial packet write" << endl;
            return false;
        }
        return true;
    }

    bool sendReady()
    {
        if (!args.control_enabled)
            return true;

        vector<uint8_t> frame;
        frame.reserve(READY_FRAME_SIZE);
        frame.insert(frame.end(), begin(CONTROL_MAGIC), end(CONTROL_MAGIC));
        append_u16_be(frame, CONTROL_VERSION);
        append_u16_be(frame, MSG_READY);
        append_u32_be(frame, READY_FRAME_SIZE);
        append_u64_be(frame, args.control_session_id);
        return sendControlFrame(frame);
    }

    bool sendCycleComplete(
        const CycleRequestFrame& request,
        const string& old_file,
        const string& new_file
    )
    {
        vector<uint8_t> frame;
        uint32_t old_file_len = static_cast<uint32_t>(old_file.size());
        uint32_t new_file_len = static_cast<uint32_t>(new_file.size());
        uint32_t total_size = CONTROL_HEADER_SIZE + 8 + 8 + 4 + 4 + old_file_len + new_file_len;
        frame.reserve(total_size);
        frame.insert(frame.end(), begin(CONTROL_MAGIC), end(CONTROL_MAGIC));
        append_u16_be(frame, CONTROL_VERSION);
        append_u16_be(frame, MSG_CYCLE_COMPLETE);
        append_u32_be(frame, total_size);
        append_u64_be(frame, request.control_session_id);
        append_u64_be(frame, request.cycle_seq);
        append_u32_be(frame, old_file_len);
        append_u32_be(frame, new_file_len);
        frame.insert(frame.end(), old_file.begin(), old_file.end());
        frame.insert(frame.end(), new_file.begin(), new_file.end());
        return sendControlFrame(frame);
    }

    bool sendCycleError(
        uint64_t control_session_id,
        uint64_t cycle_seq,
        uint32_t error_code,
        const string& message
    )
    {
        vector<uint8_t> frame;
        uint32_t message_len = static_cast<uint32_t>(message.size());
        uint32_t total_size = CONTROL_HEADER_SIZE + 8 + 8 + 4 + 4 + message_len;
        frame.reserve(total_size);
        frame.insert(frame.end(), begin(CONTROL_MAGIC), end(CONTROL_MAGIC));
        append_u16_be(frame, CONTROL_VERSION);
        append_u16_be(frame, MSG_CYCLE_ERROR);
        append_u32_be(frame, total_size);
        append_u64_be(frame, control_session_id);
        append_u64_be(frame, cycle_seq);
        append_u32_be(frame, error_code);
        append_u32_be(frame, message_len);
        frame.insert(frame.end(), message.begin(), message.end());
        return sendControlFrame(frame);
    }

    void handleCycleRequest(const zcm::ReceiveBuffer* rbuf)
    {
        CycleRequestFrame request;
        if (!parseCycleRequestFrame(rbuf->data, rbuf->data_size, &request)) {
            cerr << "Ignoring malformed logger control request" << endl;
            return;
        }
        if (request.control_session_id != args.control_session_id) {
            cerr << "Ignoring stale logger control request for session "
                 << request.control_session_id << endl;
            return;
        }

        bool resend_complete = false;
        bool send_busy = false;
        string completed_old_file;
        string completed_new_file;
        {
            unique_lock<mutex> lock{lk};

            if (pending_cycle_valid) {
                if (pending_cycle_session_id == request.control_session_id &&
                    pending_cycle_seq == request.cycle_seq) {
                    return;
                }
                send_busy = true;
            } else if (completed_cycle_valid &&
                       completed_cycle_session_id == request.control_session_id &&
                       completed_cycle_seq == request.cycle_seq) {
                resend_complete = true;
                completed_old_file = completed_cycle_old_file;
                completed_new_file = completed_cycle_new_file;
            } else {
                zcm::LogEvent* barrier = new zcm::LogEvent;
                barrier->eventnum = INTERNAL_BARRIER_EVENTNUM;
                barrier->timestamp = rbuf->recv_utime;
                barrier->channel = BARRIER_MARKER_CHANNEL;
                barrier->datalen = rbuf->data_size;
                barrier->data = new uint8_t[rbuf->data_size];
                memcpy(barrier->data, rbuf->data, sizeof(uint8_t) * rbuf->data_size);
                q.push(barrier);
                totalMemoryUsage += barrier->datalen + barrier->channel.size() + sizeof(*barrier);
                pending_cycle_valid = true;
                pending_cycle_session_id = request.control_session_id;
                pending_cycle_seq = request.cycle_seq;
            }
        }

        if (resend_complete) {
            if (!sendCycleComplete(request, completed_old_file, completed_new_file)) {
                cerr << "Fatal error re-sending cycle completion" << endl;
                exit(1);
            }
            return;
        }
        if (send_busy) {
            if (!sendCycleError(
                    request.control_session_id,
                    request.cycle_seq,
                    ERR_BUSY_WITH_OTHER_CYCLE,
                    "logger busy with another cycle")) {
                cerr << "Fatal error sending cycle busy reply" << endl;
                exit(1);
            }
            return;
        }

        newEventCond.notify_all();
    }

    void rotate_logfiles()
    {
        if (!args.quiet) cout << "Rotating log files" << endl;

        // delete log files that have fallen off the end of the rotation
        string tomove = fname_prefix + "." + to_string(args.rotate-1);
        if (FileUtil::exists(tomove))
            if (0 != FileUtil::remove(tomove))
                cerr << "ERROR! Unable to delete [" << tomove << "]" << endl;

        // Rotate away any existing log files
        for (int file_num = args.rotate-1; file_num >= 0; file_num--) {
            string newname = fname_prefix + "." + to_string(file_num);
            string tomove  = fname_prefix + "." + to_string(file_num-1);
            if (FileUtil::exists(tomove))
                if (0 != FileUtil::rename(tomove, newname))
                    cerr << "ERROR!  Unable to rotate [" << tomove << "]" << endl;
        }
    }

    bool openLogfile()
    {
        char tmp_path[PATH_MAX];

        // maybe run the filename through strftime
        if (args.use_strftime) {
            time_t now = time (NULL);
            strftime(tmp_path, sizeof(tmp_path),
                     args.input_fname.c_str(), localtime(&now));
            string new_prefix = tmp_path;

            // If auto-increment is enabled and the strftime-formatted filename
            // prefix has changed, then reset the auto-increment counter.
            if (args.auto_increment && fname_prefix != new_prefix)
                next_increment_num = 0;
            fname_prefix = std::move(new_prefix);
        } else {
            fname_prefix = args.input_fname;
        }

        if (args.auto_increment) {
            /* Loop through possible file names until we find one that doesn't
             * already exist.  This way, we never overwrite an existing file. */
            do {
                snprintf(tmp_path, sizeof(tmp_path), "%s.%04d",
                         fname_prefix.c_str(), next_increment_num);
                filename = tmp_path;
                next_increment_num++;
            } while (FileUtil::exists(filename));
        } else if (args.rotate > 0) {
            filename = fname_prefix + ".0";
        } else {
            filename = fname_prefix;
            if (!args.force_overwrite) {
                if (FileUtil::exists(filename)) {
                    cerr << "Refusing to overwrite existing file \""
                         << filename << "\"" << endl;
                    return false;
                }
            }
        }

        // create directories if needed
        string dirpart = FileUtil::dirname(filename);
        if (!FileUtil::dirExists(dirpart))
            FileUtil::mkdirWithParents(dirpart, 0755);

        if (!args.quiet) cout << "Opening log file \"" << filename << "\"" << endl;

        // open output file in append mode if we're rotating log files, or write
        // mode if not.
        log = new zcm::LogFile(filename, (args.rotate > 0) ? "a" : "w");
        if (!log->good()) {
            perror("Error: fopen failed");
            delete log;
            return false;
        }
        return true;
    }

    void handler(const zcm::ReceiveBuffer* rbuf,
                 const string& channel, size_t shardNum)
    {
        if (channel == CONTROL_REQUEST_CHANNEL) {
            handleCycleRequest(rbuf);
            return;
        }

        vector<zcm::LogEvent*> evts;

        zcm::LogEvent* le = new zcm::LogEvent;
        le->eventnum = 0;
        le->timestamp = rbuf->recv_utime;
        if (args.channel_renames.find(channel) != args.channel_renames.end()) {
            le->channel = args.channel_renames[channel];
        } else {
            le->channel = channel;
        }
        le->datalen   = rbuf->data_size;

        if (!shard_plugins[shardNum].empty()) {
            le->data = rbuf->data;

            int64_t msg_hash;
            __int64_t_decode_array(le->data, 0, 8, &msg_hash, 1);

            for (auto& p : shard_plugins[shardNum]) {
                vector<const zcm::LogEvent*> pevts =
                    p->transcodeEvent((uint64_t) msg_hash, le);
                for (auto* evt : pevts)
                    if (evt) evts.emplace_back(cloneLogEvent(evt));
                    else     evts.emplace_back(nullptr);
            }
        }

        if (evts.empty()) {
            le->data = new uint8_t[rbuf->data_size];
            memcpy(le->data, rbuf->data, sizeof(uint8_t) * rbuf->data_size);
            evts.push_back(le);
        } else {
            delete le;
        }

        bool stillRoom = true;
        {
            unique_lock<mutex> lock{lk};
            while (!evts.empty()) {
                if (!stillRoom) {
                    fprintf(stderr,
                            "Dropping message due to enforced memory constraints");
                    fprintf(stderr,
                            "Current memory estimations are at %" PRId64 " bytes",
                            totalMemoryUsage);
                    while (!evts.empty()) {
                        delete[] evts.back()->data;
                        delete evts.back();
                        evts.pop_back();
                    }
                    break;
                }

                // `back` is okay here because all the events in `evts` are from the
                // same `rbuf->recv_utime`
                zcm::LogEvent* le = evts.back();
                evts.pop_back();
                if (!le) continue;
                q.push(le);
                totalMemoryUsage += le->datalen + le->channel.size() + sizeof(*le);
                stillRoom = (args.max_target_memory == 0) ? true :
                    (totalMemoryUsage + rbuf->data_size < args.max_target_memory);
            }
        }
        newEventCond.notify_all();
    }

    void flushWhenReady()
    {
        zcm::LogEvent *le = nullptr;
        size_t qSize = 0;
        i64 memUsed = 0;
        {
            unique_lock<mutex> lock{lk};

            while (q.empty()) {
                if (done) return;
                newEventCond.wait(lock);
            }
            if (done) return;

            le = q.front();
            q.pop();
            qSize = q.size();
            memUsed = totalMemoryUsage; // want to capture the max mem used, not post flush
            totalMemoryUsage -= (le->datalen + le->channel.size() + sizeof(*le));
        }
        if (qSize != 0) ZCM_DEBUG("Queue size = %zu\n", qSize);

        bool is_internal_barrier =
            le->eventnum == INTERNAL_BARRIER_EVENTNUM &&
            le->channel == BARRIER_MARKER_CHANNEL;
        if (is_internal_barrier) {
            CycleRequestFrame request;
            if (!parseCycleRequestFrame(le->data, le->datalen, &request)) {
                sendCycleError(
                    args.control_session_id,
                    pending_cycle_seq,
                    ERR_CONTROL_PROTOCOL_INTERNAL,
                    "invalid internal barrier payload");
                cerr << "Fatal error: invalid internal barrier payload" << endl;
                exit(1);
            }

            if (log->writeEvent(le) != 0) {
                static u64 last_spew_utime = 0;
                string reason = strerror(errno);
                u64 now = TimeUtil::utime();
                if (now - last_spew_utime > 500000) {
                    cerr << "zcm_eventlog_write_event: " << reason << endl;
                    last_spew_utime = now;
                }
                sendCycleError(
                    request.control_session_id,
                    request.cycle_seq,
                    ERR_CONTROL_PROTOCOL_INTERNAL,
                    "failed to write cycle barrier marker");
                if (errno == ENOSPC)
                    exit(1);

                delete[] le->data;
                delete le;
                return;
            }

            nevents++;
            events_since_last_report++;
            logsize += 4 + 8 + 8 + 4 + le->channel.size() + 4 + le->datalen;

            string old_file = filename;
            log->close();
            if (args.rotate > 0)
                rotate_logfiles();
            if (!openLogfile()) {
                sendCycleError(
                    request.control_session_id,
                    request.cycle_seq,
                    ERR_ROTATE_OPEN_FAILED,
                    "failed to open rotated log file");
                cerr << "Fatal error: failed to open rotated log file" << endl;
                exit(1);
            }
            string new_file = filename;

            num_splits++;
            logsize = 0;
            last_report_logsize = 0;
            last_fflush_time = 0;
            {
                unique_lock<mutex> lock{lk};
                pending_cycle_valid = false;
                completed_cycle_valid = true;
                completed_cycle_session_id = request.control_session_id;
                completed_cycle_seq = request.cycle_seq;
                completed_cycle_old_file = old_file;
                completed_cycle_new_file = new_file;
            }
            if (!sendCycleComplete(request, old_file, new_file)) {
                cerr << "Fatal error sending cycle completion" << endl;
                exit(1);
            }

            delete[] le->data;
            delete le;
            return;
        }

        // Is it time to start a new logfile?
        if (args.auto_split_mb) {
            double logsize_mb = (double)logsize / (1 << 20);
            if (logsize_mb > args.auto_split_mb) {
                // Yes.  open up a new log file
                log->close();
                if (args.rotate > 0)
                    rotate_logfiles();
                if (!openLogfile()) exit(1);
                num_splits++;
                logsize = 0;
                last_report_logsize = 0;
            }
        }

        // Did we get a SIGHUP and the user wants a new logfile?
        if (got_sighup) {
            log->close();
            if (args.rotate > 0)
                rotate_logfiles();
            if (!openLogfile()) exit(1);
            num_splits++;
            logsize = 0;
            last_report_logsize = 0;

            got_sighup = 0;
        }

        if (log->writeEvent(le) != 0) {
            static u64 last_spew_utime = 0;
            string reason = strerror(errno);
            u64 now = TimeUtil::utime();
            if (now - last_spew_utime > 500000) {
                cerr << "zcm_eventlog_write_event: " << reason << endl;
                last_spew_utime = now;
            }
            if (errno == ENOSPC)
                exit(1);

            delete[] le->data;
            delete le;
            return;
        }

        // XXX (Bendes): asan reported unsigned integer overflow here
        if (args.fflush_interval_ms >= 0 &&
            (le->timestamp - last_fflush_time) > (u64)args.fflush_interval_ms * 1000) {
            Platform::fflush(log->getFilePtr());
            last_fflush_time = le->timestamp;
        }

        // bookkeeping, cleanup
        nevents++;
        events_since_last_report++;
        logsize += 4 + 8 + 8 + 4 + le->channel.size() + 4 + le->datalen;

        i64 offset_utime = le->timestamp - time0;
        if (!args.quiet && (offset_utime - last_report_time > 1000000)) {
            double dt = (offset_utime - last_report_time)/1000000.0;

            double tps =  events_since_last_report / dt;
            double kbps = (logsize - last_report_logsize) / dt / 1024.0;
            printf("Summary: %s ti:%4" PRId64 " sec  |  Events: %-9zu ( %4zu MB )  |  "
                   "TPS: %8.2f  |  KB/s: %8.2f  |  Buf Size: % 8" PRId64 " KB\n",
                   filename.c_str(),
                   offset_utime / 1000000,
                   nevents, logsize/1048576,
                   tps, kbps, memUsed / 1024);
            last_report_time = offset_utime;
            events_since_last_report = 0;
            last_report_logsize = logsize;
        }

        delete[] le->data;
        delete le;
    }

    void wakeup()
    {
        unique_lock<mutex> lock(lk);
        newEventCond.notify_all();
    }
};

Logger logger{};

static void handler(const zcm::ReceiveBuffer* rbuf, const string& channel, void* usr)
{
     logger.handler(rbuf, channel, (size_t)usr);
}

void sighandler(int signal)
{
    done++;
    logger.wakeup();
    if (done == 3) exit(1);
}

void sighup_handler(int signal)
{
    if (!logger.args.auto_increment && logger.args.rotate <= 0) return;
    got_sighup = 1;
    logger.wakeup();
}

int main(int argc, char *argv[])
{
    Platform::setstreambuf();

    if (!logger.init(argc, argv)) return 1;

    vector<unique_ptr<zcm::ZCM>> zcms;
    for (size_t i = 0; i < logger.args.shards.size(); ++i) {
        const auto& s = logger.args.shards[i];
        ZCM_DEBUG("Constructing shard with url: %s", s.zcmurl.c_str());
        zcms.emplace_back(new zcm::ZCM(s.zcmurl));
        if (!zcms.back()->good()) {
            cerr << "Couldn't initialize ZCM: " << s.zcmurl << endl
                 << "Please provide a valid zcm url either with the ZCM_DEFAULT_URL" << endl
                 << "environment variable, or with the '-u' command line argument." << endl;
            return 1;
        }

        if (s.queue_size > 0) {
            ZCM_DEBUG("Setting shard queue size to: %d", s.queue_size);
            zcms.back()->setQueueSize(s.queue_size);
        }

        for (const auto& c : s.channels) {
            ZCM_DEBUG("Subscribing to : %s", c.c_str());
            zcms.back()->subscribe(c, &handler, (void*)i);
        }
    }

    // Register signal handlers
    signal(SIGINT,  sighandler);
    signal(SIGQUIT, sighandler);
    signal(SIGTERM, sighandler);
    signal(SIGHUP,  sighup_handler);

    ZCM_DEBUG("Starting zcms");
    for (auto& z : zcms) z->start();

    while (!done) logger.flushWhenReady();

    ZCM_DEBUG("Stopping zcms");
    for (auto& z : zcms) {
        z->stop();
        z->flush();
    }

    cerr << "Logger exiting" << endl;

    return 0;
}
