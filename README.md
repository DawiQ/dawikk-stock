# dawikk-stockfish

A React Native library that integrates the powerful Stockfish chess engine for both iOS and Android platforms.

## Features

- Full UCI (Universal Chess Interface) support
- Native integration with Stockfish engine
- Cross-platform support (iOS and Android)
- Simple event-based API for communication with the engine
- Bundled with the latest Stockfish engine (version 17)
- Performance optimized for mobile devices
- Configurable event throttling to prevent UI thread blocking
- Selectable event emission to improve performance
- MultiPV support for analyzing multiple lines simultaneously
- Enhanced performance with latest analysis always available

## Installation

```sh
# Using npm
npm install dawikk-stockfish --save

# Or using Yarn
yarn add dawikk-stockfish
```

### iOS Setup

```sh
cd ios && pod install
```

## Basic Usage

```javascript
import Stockfish from 'dawikk-stockfish';

// Configure the engine (optional)
Stockfish.setConfig({
  throttling: {
    analysisInterval: 200, // Emit analysis events every 200ms
    messageInterval: 300   // Emit raw messages every 300ms
  },
  events: {
    emitMessage: true,     // Enable/disable raw message events
    emitAnalysis: true,    // Enable/disable analysis events
    emitBestMove: true     // Enable/disable bestmove events
  }
});

// Initialize the engine
await Stockfish.init();

// Set up a listener for engine output
const unsubscribeMessage = Stockfish.addMessageListener((message) => {
  console.log('Engine message:', message);
});

// Send UCI commands
await Stockfish.sendCommand('position startpos');
await Stockfish.sendCommand('go depth 15');

// Clean up when done
unsubscribeMessage();
await Stockfish.shutdown();
```

## API Reference

### Methods

#### `init()`
Initializes the Stockfish engine.

```javascript
const success = await Stockfish.init();
```

#### `setConfig(config)`
Configures the library's behavior regarding event throttling and emission. This method can be called at any time, even after initialization.

```javascript
Stockfish.setConfig({
  throttling: {
    analysisInterval: 200,  // Time in ms between analysis events
    messageInterval: 300    // Time in ms between message events
  },
  events: {
    emitMessage: true,      // Whether to emit raw message events
    emitAnalysis: true,     // Whether to emit analysis events
    emitBestMove: true      // Whether to emit bestMove events
  }
});
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
  
  // For MultiPV analysis, you can access all lines through these arrays
  if (data.bestMoves && data.bestMoves.length > 1) {
    console.log('All best moves:', data.bestMoves);
    console.log('All evaluations:', data.evaluations);
    console.log('All lines:', data.lines);
  }
});

// Later, to remove the listener
unsubscribe();
```

#### `addBestMoveListener(callback)`
Adds a dedicated listener for "bestmove" events (computer's chosen moves). Perfect for implementing a game against the computer. Returns a function to remove the listener.

```javascript
const unsubscribe = Stockfish.addBestMoveListener((data) => {
  console.log('Computer chose move:', data.move);
  // Make the move on your chess board
});

// Later, to remove the listener
unsubscribe();
```

#### `analyzePosition(fen, options)`
Helper method to set a position and start analysis.

```javascript
await Stockfish.analyzePosition('rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1', {
  depth: 20,
  multiPv: 3,
  movetime: 5000
});
```

#### `stopAnalysis()`
Stops the current analysis.

```javascript
await Stockfish.stopAnalysis();
```

#### `getComputerMove(fen, movetime, depth)`
Helper method to get a computer move in a game. Will trigger a `bestmove` event that can be captured with `addBestMoveListener`.

```javascript
// Computer has 1 second to choose a move
await Stockfish.getComputerMove('rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1', 1000, 15);
```

### Data Structures

#### StockfishConfig
```typescript
interface StockfishConfig {
  throttling: {
    analysisInterval: number;  // Time in ms between analysis event emissions
    messageInterval: number;   // Time in ms between message event emissions
  };
  events: {
    emitMessage: boolean;      // Whether to emit raw message events
    emitAnalysis: boolean;     // Whether to emit analysis events
    emitBestMove: boolean;     // Whether to emit bestMove events
  };
}
```

#### AnalysisData
```typescript
interface AnalysisData {
  type: 'info' | 'bestmove';
  depth?: number;
  score?: number;
  mate?: number;
  bestMove?: string;
  line?: string;
  move?: string;
  
  // For MultiPV analysis
  bestMoves?: string[];        // Array of best moves for each line
  evaluations?: string[];      // Array of evaluations for each line
  lines?: string[];            // Array of move sequences for each line
  depths?: number[];           // Array of depths for each line
}
```

#### BestMoveData
```typescript
interface BestMoveData {
  type: 'bestmove';
  move: string;
  ponder?: string;
}
```

#### AnalysisOptions
```typescript
interface AnalysisOptions {
  depth?: number;
  multiPv?: number;
  movetime?: number;
  nodes?: number;
}
```

## Events

The library emits three types of events:

1. **Raw Messages** - Plain text output from the Stockfish engine
   - These include UCI protocol responses like `uciok`, `readyok`, and `bestmove e2e4`
   - Access these via `addMessageListener`
   - Can be disabled with `setConfig({events: {emitMessage: false}})`

2. **Analysis Data** - Structured data parsed from engine output
   - This data may include evaluation scores, depth, best moves, etc.
   - Support for MultiPV (multiple lines) with arrays for bestMoves, evaluations, and lines
   - Access this via `addAnalysisListener`
   - Can be disabled with `setConfig({events: {emitAnalysis: false}})`

3. **Computer Moves** - Dedicated events for computer moves
   - Contains only the final move choice from the engine
   - Access this via `addBestMoveListener`
   - Can be disabled with `setConfig({events: {emitBestMove: false}})`

## Throttling Configuration

To prevent UI thread blocking with rapid engine updates:

```javascript
// Configure throttling
Stockfish.setConfig({
  throttling: {
    analysisInterval: 200,  // Emit analysis updates every 200ms max
    messageInterval: 300    // Emit raw message updates every 300ms max
  }
});
```

The throttling system ensures:
- Events are buffered and emitted at regular intervals
- Only the most recent data is emitted, preventing outdated analysis
- For MultiPV analysis, all lines are collected and emitted together
- UI remains responsive even during intensive analysis

## Usage Examples

### Position Analysis with MultiPV

```javascript
import Stockfish from 'dawikk-stockfish';

// Initialize the engine
await Stockfish.init();

// Configure for performance
Stockfish.setConfig({
  throttling: {
    analysisInterval: 200,
    messageInterval: 300
  },
  events: {
    emitMessage: false,  // Disable raw messages (not needed for analysis)
    emitAnalysis: true,
    emitBestMove: false  // Disable bestmove events (not needed for analysis)
  }
});

// Set up analysis listener
const unsubscribe = Stockfish.addAnalysisListener((data) => {
  // For MultiPV analysis, we'll receive arrays of data
  if (data.bestMoves && data.bestMoves.length > 0) {
    console.log('Analysis depth:', Math.max(...data.depths));
    
    // Display each line
    data.bestMoves.forEach((move, index) => {
      console.log(`Line ${index + 1}:`);
      console.log(`  Best move: ${move}`);
      console.log(`  Evaluation: ${data.evaluations[index]}`);
      console.log(`  Full line: ${data.lines[index]}`);
    });
  }
});

// Analyze a position with 3 lines
await Stockfish.analyzePosition('r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3', {
  depth: 18,
  multiPv: 3
});

// Stop after 5 seconds
setTimeout(async () => {
  await Stockfish.stopAnalysis();
  unsubscribe();
  await Stockfish.shutdown();
}, 5000);
```

### Playing Against the Computer (Optimized)

```javascript
import Stockfish from 'dawikk-stockfish';
import { Chess } from 'chess.js'; // Assuming you use chess.js

class ChessGame {
  constructor() {
    this.game = new Chess();
    this.initialize();
  }

  async initialize() {
    // Configure the engine for optimal performance in a game
    Stockfish.setConfig({
      throttling: {
        analysisInterval: 200,
        messageInterval: 300
      },
      events: {
        emitMessage: false,  // No need for raw messages
        emitAnalysis: false, // No need for analysis data
        emitBestMove: true   // Only need the final move
      }
    });
    
    // Initialize engine
    await Stockfish.init();
    
    // Listen for computer moves
    this.unsubscribe = Stockfish.addBestMoveListener((data) => {
      // Execute computer's move
      this.game.move({
        from: data.move.substring(0, 2),
        to: data.move.substring(2, 4),
        promotion: data.move.length > 4 ? data.move[4] : undefined
      });
      
      console.log('New position:', this.game.fen());
      console.log('History:', this.game.history({ verbose: true }));
    });
  }

  // Method to make player's move
  makeMove(from, to, promotion) {
    // Check if move is legal
    const move = this.game.move({ from, to, promotion });
    
    if (move) {
      // Ask computer to respond
      Stockfish.getComputerMove(this.game.fen(), 1000, 15);
      return true;
    }
    
    return false;
  }

  // Clean up resources
  cleanup() {
    this.unsubscribe();
    Stockfish.shutdown();
  }
}

// Usage
const game = new ChessGame();
game.makeMove('e2', 'e4'); // Player makes first move
// ... after receiving bestmove event, computer responds automatically
```

## Advanced: Creating Multiple Instances

For advanced use cases (like running multiple instances), you can create custom instances:

```javascript
import { Stockfish } from 'dawikk-stockfish'; // Import the class instead of the default instance

// Create separate instances with different configurations
const analysisEngine = new Stockfish({
  throttling: { analysisInterval: 100, messageInterval: 200 },
  events: { emitMessage: false, emitAnalysis: true, emitBestMove: false }
});

const gameEngine = new Stockfish({
  throttling: { analysisInterval: 300, messageInterval: 400 },
  events: { emitMessage: false, emitAnalysis: false, emitBestMove: true }
});

// Initialize both engines
await analysisEngine.init();
await gameEngine.init();

// Use them independently
analysisEngine.addAnalysisListener(data => console.log('Analysis:', data));
gameEngine.addBestMoveListener(data => console.log('Game move:', data.move));

// Clean up when done
analysisEngine.destroy();
gameEngine.destroy();
```

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
Stockfish.addBestMoveListener((data) => {
  const moveStr = data.move;
  // Parse UCI format to your chess representation
  const from = moveStr.substring(0, 2);
  const to = moveStr.substring(2, 4);
  const promotion = moveStr.length > 4 ? moveStr[4] : undefined;
  
  // Now you can use these coordinates with a chess library like chess.js
  chess.move({from, to, promotion});
});
```

## Performance Optimization Tips

1. **Disable Unnecessary Events**: Use `setConfig()` to disable events you don't need
   ```javascript
   // For computer game, disable analysis events
   Stockfish.setConfig({
     events: { emitMessage: false, emitAnalysis: false, emitBestMove: true }
   });
   
   // For position analysis, disable bestmove events
   Stockfish.setConfig({
     events: { emitMessage: false, emitAnalysis: true, emitBestMove: false }
   });
   ```

2. **Adjust Throttling**: Increase intervals for smoother UI, decrease for more responsive analysis
   ```javascript
   // More responsive analysis (updates more frequently)
   Stockfish.setConfig({
     throttling: { analysisInterval: 100, messageInterval: 150 }
   });
   
   // Smoother UI (less frequent updates)
   Stockfish.setConfig({
     throttling: { analysisInterval: 300, messageInterval: 400 }
   });
   ```

3. **Minimize Listeners**: Remove listeners when not needed
   ```javascript
   const unsubscribe = Stockfish.addAnalysisListener(data => {/*...*/});
   
   // When done with analysis
   unsubscribe();
   ```

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

// Start analysis with multiple lines (MultiPV)
await Stockfish.sendCommand('setoption name MultiPV value 3');
await Stockfish.sendCommand('go depth 20');

// Limit engine strength (0-20)
await Stockfish.sendCommand('setoption name Skill Level value 10');

// Stop the current calculation
await Stockfish.sendCommand('stop');
```

## License

This project is licensed under the GPL-3.0 License, as it includes Stockfish code which is GPL-3.0 licensed.

For more information about Stockfish, visit [stockfishchess.org](https://stockfishchess.org/)