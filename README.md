# dawikk-stockfish

A React Native library that integrates the powerful Stockfish chess engine for both iOS and Android platforms.

## Features

- Full UCI (Universal Chess Interface) support
- Native integration with Stockfish engine
- Cross-platform support (iOS and Android)
- Simple event-based API for communication with the engine
- Bundled with the latest Stockfish engine (version 17)
- Performance optimized for mobile devices

## Prerequisites

### Android
- Android SDK build tools
- Android SDK Command line tools

### iOS
- Xcode 12 or newer
- CocoaPods

## Installation

```sh
# Using npm
npm install react-native-stockfish --save

# Or using Yarn
yarn add react-native-stockfish
```

### iOS Setup

```sh
cd ios && pod install
```

## Usage

```javascript
import Stockfish from 'react-native-stockfish';

// Initialize the engine when your component mounts
useEffect(() => {
  const setupEngine = async () => {
    try {
      // Initialize the engine
      await Stockfish.init();
      
      // Set up listeners for engine output
      const messageListener = Stockfish.addMessageListener((message) => {
        console.log('Stockfish message:', message);
        
        // Look for bestmove messages to handle them
        if (message.startsWith('bestmove ')) {
          const moveStr = message.split(' ')[1];
          console.log('Engine suggested move:', moveStr);
        }
      });
      
      // Optional: Set up analysis listener for structured data
      const analysisListener = Stockfish.addAnalysisListener((data) => {
        console.log('Analysis data:', data);
      });
      
      // Start UCI communication
      await Stockfish.sendCommand('uci');
      await Stockfish.sendCommand('isready');
      
      // Set position and start analysis
      await Stockfish.sendCommand('position startpos');
      await Stockfish.sendCommand('go depth 15');
      
      // Clean up when component unmounts
      return () => {
        messageListener();  // Remove message listener
        analysisListener(); // Remove analysis listener
        Stockfish.sendCommand('quit');
        Stockfish.shutdown();
      };
    } catch (error) {
      console.error('Error setting up Stockfish:', error);
    }
  };
  
  setupEngine();
}, []);
```

## API Reference

### Methods

#### `init()`
Initializes the Stockfish engine.

```javascript
const success = await Stockfish.init();
```

#### `sendCommand(command)`
Sends a UCI command to the engine.

```javascript
await Stockfish.sendCommand('position fen rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1');
await Stockfish.sendCommand('go depth 20');
```

#### `shutdown()`
Shuts down the engine and frees resources.

```javascript
await Stockfish.shutdown();
```

#### `addMessageListener(callback)`
Adds a listener for raw output messages from the engine. Returns a function to remove the listener.

```javascript
const unsubscribe = Stockfish.addMessageListener((message) => {
  console.log('Engine says:', message);
});

// Later, to remove the listener
unsubscribe();
```

#### `addAnalysisListener(callback)`
Adds a listener for parsed analysis data (structured data). Returns a function to remove the listener.

```javascript
const unsubscribe = Stockfish.addAnalysisListener((data) => {
  console.log('Analysis data:', data);
});

// Later, to remove the listener
unsubscribe();
```

### Events

The library emits two types of events:

1. **Raw Messages** - Plain text output from the Stockfish engine
   - These include UCI protocol responses like `uciok`, `readyok`, and `bestmove e2e4`
   - Access these via `addMessageListener`

2. **Analysis Data** - Structured data parsed from engine output
   - This data may include evaluation scores, depth, best moves, etc.
   - Access this via `addAnalysisListener`

## Important Notes

### Engine Initialization Sequence

For proper operation, follow this initialization sequence:

1. Initialize the engine with `await Stockfish.init()`
2. Set up your listeners
3. Send the UCI command: `await Stockfish.sendCommand('uci')`
4. Wait for the `uciok` response in your message listener
5. Send the `isready` command: `await Stockfish.sendCommand('isready')`
6. Wait for the `readyok` response in your message listener
7. Now the engine is ready to accept further commands

### Handling Engine Moves

The Stockfish engine communicates moves in the UCI format (e.g., `e2e4`). To handle these:

```javascript
Stockfish.addMessageListener((message) => {
  if (message.startsWith('bestmove ')) {
    const parts = message.split(' ');
    if (parts.length >= 2) {
      const moveStr = parts[1];
      // Parse UCI format to your chess representation
      const from = moveStr.substring(0, 2);
      const to = moveStr.substring(2, 4);
      const promotion = moveStr.length > 4 ? moveStr[4] : undefined;
      
      // Now you can use these coordinates with a chess library like chess.js
      chess.move({from, to, promotion});
    }
  }
});
```

### Position Validation

Always validate positions before sending them to the engine:

- Check for legal positions using a library like [chess.js](https://github.com/jhlywa/chess.js)
- Ensure the position has one king for each side
- Verify that the non-moving side's king is not in check

Sending invalid positions can cause the engine to crash or produce unexpected results.

## Common UCI Commands

Here are some common UCI commands you can send to the engine:

```javascript
// Set up the starting position
await Stockfish.sendCommand('position startpos');

// Set up a position from FEN
await Stockfish.sendCommand('position fen r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3');

// Set up a position and apply moves
await Stockfish.sendCommand('position startpos moves e2e4 e7e5 g1f3');

// Start analysis with depth limit
await Stockfish.sendCommand('go depth 20');

// Start analysis with time limit (milliseconds)
await Stockfish.sendCommand('go movetime 3000');

// Limit engine strength (0-20)
await Stockfish.sendCommand('setoption name Skill Level value 10');

// Stop the current calculation
await Stockfish.sendCommand('stop');
```

## Example: Complete Chess Application

```jsx
import React, { useEffect, useState } from 'react';
import { View, Text, Button } from 'react-native';
import Stockfish from 'react-native-stockfish';
import { Chess } from 'chess.js';

export default function ChessEngine() {
  const [engineReady, setEngineReady] = useState(false);
  const [thinking, setThinking] = useState(false);
  const [evaluation, setEvaluation] = useState(null);
  const [game] = useState(new Chess());

  useEffect(() => {
    let messageUnsubscribe, analysisUnsubscribe;
    
    const initEngine = async () => {
      try {
        // Initialize engine
        await Stockfish.init();
        
        // Set up listeners
        messageUnsubscribe = Stockfish.addMessageListener(handleEngineMessage);
        analysisUnsubscribe = Stockfish.addAnalysisListener(handleEngineAnalysis);
        
        // Start UCI protocol
        await Stockfish.sendCommand('uci');
      } catch (error) {
        console.error('Failed to initialize engine:', error);
      }
    };
    
    initEngine();
    
    // Cleanup
    return () => {
      if (messageUnsubscribe) messageUnsubscribe();
      if (analysisUnsubscribe) analysisUnsubscribe();
      Stockfish.sendCommand('quit');
      Stockfish.shutdown();
    };
  }, []);
  
  const handleEngineMessage = (message) => {
    console.log('Engine message:', message);
    
    if (message.includes('uciok')) {
      Stockfish.sendCommand('isready');
    } 
    else if (message.includes('readyok')) {
      setEngineReady(true);
    }
    else if (message.startsWith('bestmove ')) {
      setThinking(false);
      
      const parts = message.split(' ');
      if (parts.length >= 2) {
        const moveStr = parts[1];
        
        if (moveStr !== '(none)' && moveStr !== 'NULL') {
          // Parse UCI format
          const from = moveStr.substring(0, 2);
          const to = moveStr.substring(2, 4);
          const promotion = moveStr.length > 4 ? moveStr[4] : undefined;
          
          // Make the move
          game.move({from, to, promotion});
          
          // Update state with new position
          setEvaluation(null);
        }
      }
    }
  };
  
  const handleEngineAnalysis = (data) => {
    if (data.type === 'info' && data.score !== undefined) {
      setEvaluation(data.score);
    }
  };
  
  const getEngineMove = async () => {
    if (!engineReady || thinking) return;
    
    setThinking(true);
    
    // Send current position to engine
    await Stockfish.sendCommand(`position fen ${game.fen()}`);
    
    // Set engine options
    await Stockfish.sendCommand('setoption name Skill Level value 10');
    
    // Start calculation
    await Stockfish.sendCommand('go movetime 1000');
  };
  
  return (
    <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center', padding: 20 }}>
      <Text style={{ marginBottom: 20 }}>
        Engine status: {engineReady ? 'Ready' : 'Initializing...'}
      </Text>
      
      <Text style={{ marginBottom: 20 }}>
        Current FEN: {game.fen()}
      </Text>
      
      {evaluation !== null && (
        <Text style={{ marginBottom: 20 }}>
          Evaluation: {evaluation > 0 ? '+' : ''}{evaluation.toFixed(2)}
        </Text>
      )}
      
      <Button
        title={thinking ? "Engine thinking..." : "Get Engine Move"}
        onPress={getEngineMove}
        disabled={!engineReady || thinking}
      />
      
      <Button
        title="Reset Position"
        onPress={() => {
          game.reset();
          setEvaluation(null);
        }}
        disabled={thinking}
      />
    </View>
  );
}
```

## Troubleshooting

### Engine not responding to commands

Ensure you're following the proper UCI protocol initialization sequence:
1. Initialize the engine
2. Send 'uci' command
3. Wait for 'uciok' response
4. Send 'isready' command
5. Wait for 'readyok' response

### Communication issues or crashed engine

If you experience issues with the engine crashing or not responding:

1. Check that positions sent to the engine are valid
2. Ensure you're properly handling the engine startup and shutdown
3. Make sure to remove event listeners when your component unmounts
4. For complex positions, increase the hash size: `await Stockfish.sendCommand('setoption name Hash value 64')`

### Debugging engine output

To get more insight into what's happening with the engine, log all messages:

```javascript
Stockfish.addMessageListener((message) => {
  console.log('DEBUG Stockfish raw output:', message);
});
```

## Updating Stockfish Version

If you need to update the Stockfish engine version:

1. Replace the code in the `cpp/stockfish` directory with the new version's source code
2. Update the NNUE file references in `cpp/bridge/stockfish_bridge.cpp`
3. Rebuild the library

## License

This project is licensed under the GPL-3.0 License, as it includes Stockfish code which is GPL-3.0 licensed.

For more information about Stockfish, visit [stockfishchess.org](https://stockfishchess.org/).