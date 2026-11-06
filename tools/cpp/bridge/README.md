# Bridge transport adapters

The bridge uses `zcm::ZCM` by default. Builds that route channels across multiple
local buses can provide a compatible subclass without embedding application
channel policy in ZCM:

```sh
export CXXFLAGS="$CXXFLAGS -I/path/to/adapter -DZCM_BRIDGE_TRANSPORT_HEADER=<RoutedZcm.hpp> -DZCM_BRIDGE_TRANSPORT_TYPE=dexgpt::RoutedZcm"
./waf configure ...
./waf build
```

The adapter must accept a URL string, derive from `zcm::ZCM`, and implement its
virtual publish, subscribe, lifecycle, and flush operations. Bridge callbacks
serialize shared decimation/plugin state when the adapter dispatches from
multiple buses. Stopping the adapter must join its callbacks before returning.
