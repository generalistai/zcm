using ZCM
using Test

@testset "Channel lengths and subscription regexes" begin
    for url in ("block-inproc", "nonblock-inproc")
        z = Zcm(url)
        @test good(z)
        dispatch = url == "block-inproc" ? handle : handle_nonblock
        for channel in (repeat("a", CHANNEL_MAXLEN), repeat("é", CHANNEL_MAXLEN ÷ 2))
            received = []
            sub = subscribe(z, channel, (rbuf, name, data) -> push!(received, (name, copy(data))))
            @test publish(z, channel, UInt8[42]) == 0
            @test dispatch(z) == 0
            @test received == [(channel, UInt8[42])]
            @test unsubscribe(z, sub) == 0
        end
        for channel in (repeat("a", CHANNEL_MAXLEN + 1), repeat("é", CHANNEL_MAXLEN ÷ 2 + 1))
            @test_throws ArgumentError publish(z, channel, UInt8[42])
            @test_throws ArgumentError subscribe(z, channel, (rbuf, name, data) -> nothing)
        end
        pattern = "(" * repeat("a", CHANNEL_MAXLEN) * "|event)"
        @test_throws ArgumentError publish(z, pattern, UInt8[42])
        if url == "block-inproc"
            received = []
            sub = subscribe(z, pattern, (rbuf, name, data) -> push!(received, name))
            @test publish(z, "event", UInt8[42]) == 0
            @test dispatch(z) == 0
            @test received == ["event"]
            @test unsubscribe(z, sub) == 0
        else
            @test_throws ArgumentError subscribe(z, pattern, (rbuf, name, data) -> nothing)
        end
        ZCM.destroy(z)
    end
end

@testset "Permanent IPC subscription failures" begin
    mktempdir("/tmp"; prefix="zcm-ipc-") do directory
        z = Zcm("ipc://" * basename(directory))
        @test good(z)
        @test_throws ErrorException subscribe(z, repeat("a", CHANNEL_MAXLEN), (r, c, d) -> nothing)
        sub = subscribe(z, "event", (r, c, d) -> nothing)
        @test unsubscribe(z, sub) == 0
        ZCM.destroy(z)
    end
end
