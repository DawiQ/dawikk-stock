require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "dawikk-stock"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"] || "https://github.com/DawiQ/dawikk-stock"
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => "11.0" }
  s.source       = { :git => "https://github.com/DawiQ/dawikk-stock.git", :tag => "#{s.version}" }

  # Definiujemy wszystkie pliki źródłowe
  s.source_files = [
    "ios/**/*.{h,m,mm}", 
    "cpp/bridge/**/*.{h,cpp}",
    "cpp/stockfish/*.{h,cpp}",
    "cpp/stockfish/nnue/*.{h,cpp}",
    "cpp/stockfish/nnue/features/*.{h,cpp}",
    "cpp/stockfish/nnue/layers/*.h",
    "cpp/stockfish/syzygy/*.{h,cpp}"
  ]
  
  # Definiujemy nagłówki prywatne
  s.private_header_files = "cpp/bridge/stockfish_bridge.h"

  # Wykluczamy plik main.cpp, jeśli istnieje
  s.exclude_files = "cpp/stockfish/main.cpp"

  # Zasoby - pliki NNUE
  s.resource_bundles = {
    'StockfishNNUE' => ['cpp/*.nnue']
  }
  
  # Ustawienia C++
  s.pod_target_xcconfig = { 
    "CLANG_CXX_LANGUAGE_STANDARD" => "c++17",
    "CLANG_CXX_LIBRARY" => "libc++",
    "OTHER_CPLUSPLUSFLAGS" => "-DUSE_PTHREADS -DNDEBUG -DIS_64BIT -Wno-comma -Wno-deprecated-declarations -DNO_INCBIN",
    "HEADER_SEARCH_PATHS" => "\"$(PODS_TARGET_SRCROOT)/cpp\""
  }

  s.dependency "React-Core"
end