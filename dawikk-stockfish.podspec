require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "dawikk-stockfish"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"] || "https://github.com/DawiQ/dawikk-stock"
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => "15.1" }
  s.source       = { :git => "https://github.com/DawiQ/dawikk-stock.git", :tag => "#{s.version}" }

  # Stockfish 19 sources (NNUE, network loaded from file). cpp/stockfish/universal
  # is not vendored: it holds upstream's runtime-CPU-dispatch binary, and we ship a
  # single arm64 slice instead.
  s.source_files = [
    "ios/**/*.{h,m,mm}",
    "cpp/bridge/**/*.{h,cpp}",
    "cpp/stockfish/*.{h,cpp}",
    "cpp/stockfish/nnue/*.{h,cpp}",
    "cpp/stockfish/nnue/features/*.{h,cpp}",
    "cpp/stockfish/nnue/layers/*.h",
    "cpp/stockfish/syzygy/*.{h,cpp}",
    "cpp/stockfish/incbin/*.h"
  ]

  s.private_header_files = "cpp/bridge/stockfish_bridge.h"

  # Exclude main.cpp from Stockfish sources (it defines main())
  s.exclude_files = "cpp/stockfish/main.cpp"

  # The NNUE network is NOT bundled: ~94 MB would be most of the App Store
  # download. It is fetched into Application Support/nnue on first use by
  # NnueDownloader and handed to the engine through the EvalFile UCI option.
  # Stockfish 19 retired the second (small) network, so there is one file now.

  # Network.framework: NnueDownloader asks whether the only path out is a
  # metered one before it starts a 94 MB transfer.
  s.frameworks = "Network"

  # -fconstexpr-steps: Stockfish 19 builds its attack tables at compile time and
  # blows past Clang's default 1,048,576-step constexpr budget doing it. Upstream's
  # own Makefile passes exactly this for every clang target; without it attacks.cpp
  # fails with "constexpr evaluation hit maximum step limit".
  s.compiler_flags = '-Wno-comma -Wno-deprecated-declarations -Wno-shorten-64-to-32 -Wno-unused-variable -fconstexpr-steps=500000000'

  s.pod_target_xcconfig = {
    "CLANG_CXX_LANGUAGE_STANDARD" => "c++17",
    "CLANG_CXX_LIBRARY" => "libc++",
    "HEADER_SEARCH_PATHS" => "\"$(PODS_TARGET_SRCROOT)/cpp/stockfish\" \"$(PODS_TARGET_SRCROOT)/cpp/bridge\"",
    # Common defines. NNUE_EMBEDDING_OFF keeps the binary small: the network is
    # loaded from the downloaded .nnue file via the EvalFile UCI option instead of
    # being embedded with incbin.
    #
    # STOCKFISH_EMBEDDED switches off the std::exit(1) that Stockfish 19 added to
    # UCIEngine::terminate_on_critical_error. Upstream runs as its own process, so
    # ending it on a malformed FEN or an illegal move costs nothing; here the engine
    # shares the app's process and that exit would take the app down with it. See
    # the LOCAL PATCH comments in cpp/stockfish/uci.{h,cpp}.
    "GCC_PREPROCESSOR_DEFINITIONS" => "USE_PTHREADS=1 NDEBUG=1 IS_64BIT=1 NNUE_EMBEDDING_OFF=1 STOCKFISH_EMBEDDED=1 USE_POPCNT=1 USE_PREFETCH=1",
    # ARM64 (iOS devices + Apple Silicon simulator) use NEON SIMD.
    # Dot-product is intentionally NOT enabled so the binary runs on A9/A10
    # devices still supported by the iOS 15.1 deployment target. x86_64
    # (Intel simulator) falls back to the scalar NNUE path.
    "OTHER_CPLUSPLUSFLAGS[arch=arm64]" => "-DUSE_NEON=8"
  }

  s.dependency "React-Core"
end
