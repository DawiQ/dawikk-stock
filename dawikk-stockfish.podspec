require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "dawikk-stockfish"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"] || "https://github.com/DawiQ/dawikk-stock"
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => "11.0" }
  s.source       = { :git => "https://github.com/DawiQ/dawikk-stock.git", :tag => "#{s.version}" }

  s.source_files = [
    "ios/**/*.{h,m,mm}", 
    "cpp/bridge/**/*.{h,cpp}",
    "cpp/stockfish/*.{h,cpp}",
    "cpp/stockfish/nnue/*.{h,cpp}",
    "cpp/stockfish/nnue/features/*.{h,cpp}",
    "cpp/stockfish/nnue/layers/*.h",
    "cpp/stockfish/syzygy/*.{h,cpp}"
  ]
  
  s.private_header_files = "cpp/bridge/stockfish_bridge.h"

  s.exclude_files = "cpp/stockfish/main.cpp"

  # Download NNUE files before build
  s.prepare_command = <<-CMD
    # Download to cpp directory if not exists
    if [ ! -f cpp/nn-37f18f62d772.nnue ]; then
      echo "Downloading nn-37f18f62d772.nnue..."
      curl -L -o cpp/nn-37f18f62d772.nnue https://tests.stockfishchess.org/api/nn/nn-37f18f62d772.nnue
    fi
    if [ ! -f cpp/nn-1c0000000000.nnue ]; then
      echo "Downloading nn-1c0000000000.nnue..."
      curl -L -o cpp/nn-1c0000000000.nnue https://tests.stockfishchess.org/api/nn/nn-1c0000000000.nnue
    fi
    
    # INCBIN searches relative to source file location (cpp/stockfish/nnue/network.cpp)
    # So we need files in cpp/stockfish/nnue/
    mkdir -p cpp/stockfish/nnue
    cp cpp/nn-37f18f62d772.nnue cpp/stockfish/nnue/nn-37f18f62d772.nnue 2>/dev/null || true
    cp cpp/nn-1c0000000000.nnue cpp/stockfish/nnue/nn-1c0000000000.nnue 2>/dev/null || true
    
    # Also in cpp/stockfish/ as backup
    cp cpp/nn-37f18f62d772.nnue cpp/stockfish/nn-37f18f62d772.nnue 2>/dev/null || true
    cp cpp/nn-1c0000000000.nnue cpp/stockfish/nn-1c0000000000.nnue 2>/dev/null || true
  CMD
  
  # Add script phase to ensure NNUE files are in place before compilation
  s.script_phases = [
    {
      :name => 'Copy NNUE Files Before Compile',
      :script => '
        set -e
        NNUE_SOURCE="${PODS_TARGET_SRCROOT}/cpp"
        NNUE_DEST1="${PODS_TARGET_SRCROOT}/cpp/stockfish/nnue"
        NNUE_DEST2="${PODS_TARGET_SRCROOT}/cpp/stockfish"
        
        echo "Copying NNUE files for compilation..."
        
        mkdir -p "$NNUE_DEST1"
        mkdir -p "$NNUE_DEST2"
        
        if [ -f "${NNUE_SOURCE}/nn-37f18f62d772.nnue" ]; then
          cp -f "${NNUE_SOURCE}/nn-37f18f62d772.nnue" "${NNUE_DEST1}/"
          cp -f "${NNUE_SOURCE}/nn-37f18f62d772.nnue" "${NNUE_DEST2}/"
          echo "Copied nn-37f18f62d772.nnue"
        else
          echo "ERROR: nn-37f18f62d772.nnue not found!"
          exit 1
        fi
        
        if [ -f "${NNUE_SOURCE}/nn-1c0000000000.nnue" ]; then
          cp -f "${NNUE_SOURCE}/nn-1c0000000000.nnue" "${NNUE_DEST1}/"
          cp -f "${NNUE_SOURCE}/nn-1c0000000000.nnue" "${NNUE_DEST2}/"
          echo "Copied nn-1c0000000000.nnue"
        else
          echo "ERROR: nn-1c0000000000.nnue not found!"
          exit 1
        fi
        
        ls -la "${NNUE_DEST1}/"*.nnue || true
      ',
      :execution_position => :before_compile
    }
  ]
  
  # Include NNUE files as resources - they will be loaded at runtime
  s.resources = ["cpp/*.nnue"]
  
  # Preserve NNUE files in multiple locations
  s.preserve_paths = "cpp/*.nnue", "cpp/stockfish/*.nnue", "cpp/stockfish/nnue/*.nnue"
  
  s.compiler_flags = '-Wno-comma -Wno-deprecated-declarations'
  
  s.pod_target_xcconfig = { 
    "CLANG_CXX_LANGUAGE_STANDARD" => "c++17",
    "CLANG_CXX_LIBRARY" => "libc++",
    "HEADER_SEARCH_PATHS" => "\"$(PODS_TARGET_SRCROOT)/cpp/stockfish\" \"$(PODS_TARGET_SRCROOT)/cpp/bridge\""
  }
  
  s.user_target_xcconfig = {
    "GCC_PREPROCESSOR_DEFINITIONS" => "NNUE_EMBEDDING_OFF=1 USE_PTHREADS=1 NDEBUG=1 IS_64BIT=1",
    "OTHER_CPLUSPLUSFLAGS" => "-DNNUE_EMBEDDING_OFF -DUSE_PTHREADS -DNDEBUG -DIS_64BIT"
  }

  s.dependency "React-Core"
end