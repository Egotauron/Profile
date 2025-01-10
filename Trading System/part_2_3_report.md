# Part II and Part III : Algorithmic Trading System - Implementation Analysis

## Introduction

This report analyses our implementation of a high-frequency trading system using Rust, as opposed to Python in Part 1 of the assignment. The choice of Rust as the implementation language is due to the the crucial advantages for financial systems, offering much faster performance combined with memory safety and thread security guarantees. 

It also has the added benefit of being the language used in my current workplace and given I am trying to move to a trading role, I took this as an opportunity to develop the live trading code in the same language used by my colleagues.

The code was developed after the back test in Python and uses the best parameters discovered in the back test for both types of strategies. Like in the back test, I have linked to the Binance test-net to retrieve the data and send trades.  

The code relies on a separate config file that contains the strategy parameters as well as the API keys to implement the strategy. It contains a toggle for debugging messages and as you will be able to see from the code, there are plenty of those. 

In the report below I will outline highlights of the Rust implementation whilst also providing an overview of the system. The report will focus on highlighting the components required in Part II and Part III of the assignment. I will then give more colour to live trading error handling to ensure the system is robust to trade in a live environment.  Finally I will provide a deeper dive in the main trading loop and the ancillary functions.

## Architecture overview
![[mermaid-diagram-2024-12-17-210825 1.png]]

## Part II: Broker API Integration

### REST API Implementation and Professional Standards

The system implements a REST API integration that goes beyond basic data retrieval. The implementation uses the `reqwest` client for HTTP communications, with an authentication system using HMAC-SHA256 signatures. This approach ensures secure and efficient communication with the exchange:

```rust
fn place_order(
    client: &Client,
    config: &TradingConfig,
    side: &str,
    price: f64,
    state: &mut TradingState,
    quantity: f64,
) -> Result<(), Box<dyn Error>> {
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)?
        .as_millis() as u64;
    
    let params = format!(
        "symbol={}&side={}&type=MARKET&quantity={}&timestamp={}",
        config.symbol, side, quantity, timestamp
    );
```

The order placement system includes comprehensive error handling and verification. Instead of using basic data sources like Yahoo Finance, the system connects directly to the Binance API, providing access to high-quality market data and professional-grade execution capabilities.

### Real-Time Data Processing

The system implements WebSocket connections for real-time market data processing, handling multiple data streams simultaneously:

```rust
let streams = format!(
    "{}@kline_1m/{}@bookTicker/{}@depth{}@100ms",
    symbol_lower, symbol_lower, symbol_lower, config.depth_levels
);
```

This approach allows the system to maintain an accurate view of market conditions by combining candlestick data, order book updates, and trade information. The implementation includes sophisticated data validation and processing:

```rust
struct MarketDataValidator {
    last_price: f64,
    max_price_change: f64,
    min_volume: f64,
    price_history: VecDeque<f64>,
    volume_history: VecDeque<f64>,
    last_depth_print: SystemTime,
}
```

### Order Management and Execution

The system implements a comprehensive order management system that handles various aspects of trade execution:

```rust
struct Position {
    size: f64,
    average_price: f64,
    last_update: SystemTime,
    value_in_usdt: f64,
    unrealized_pnl: f64,
    last_trade_time: SystemTime,
}
```

This structure maintains detailed position information and includes methods for position verification and updates, ensuring accurate trade tracking and risk management.

## Part III: Risk Management and Testing

### Event Handling and Verification

The implementation includes robust event handling and verification mechanisms. Server responses are carefully validated:

```rust
if response.status().is_success() {
    state.record_trade(side.to_string(), quantity, price, config.symbol.clone());
    state.update_position(side, quantity, price);
} else {
    let error_text = response.text().await?;
    Err(format!("Order failed: {}", error_text).into())
}
```

Position tracking includes verification mechanisms to ensure consistency between expected and actual positions:

```rust
fn verify_position(&self, expected_size: f64, expected_value: f64, tolerance: f64) -> bool {
    let size_diff = (self.size - expected_size).abs();
    let value_diff = (self.value_in_usdt - expected_value).abs();
    size_diff < tolerance && value_diff < (expected_value * tolerance)
}
```

### Risk Metrics and Monitoring

The system implements comprehensive risk monitoring through the RiskMetrics structure:

```rust
struct RiskMetrics {
    var_window: Vec<f64>,
    var_window_size: usize,
    max_drawdown: f64,
    peak_value: f64,
    current_drawdown: f64,
    turnover: f64,
    last_var: f64,
}
```

This structure enables real-time calculation of critical risk metrics including Value at Risk (VaR), maximum drawdown, and position turnover. The system updates these metrics continuously:

```rust
fn update_risk_metrics(&mut self, price: f64, trade_value: f64) {
    if self.trades.len() > 1 {
        let last_trade = &self.trades[self.trades.len() - 2];
        let return_value = (price - last_trade.price) / last_trade.price;
        self.risk_metrics.update_var(return_value);
    }
}
```

### Market Impact Analysis

The system includes market impact analysis capabilities:

```rust
fn analyze_liquidity(&self, size: f64, side: &str) -> Option<LiquidityMetrics> {
    let metrics = LiquidityMetrics {
        total_available,
        levels_needed,
        top_level_volume,
        price_impact,
        average_level_volume,
    };
}
```

This analysis helps ensure that trading activities do not adversely affect market prices, particularly important for larger order sizes. 

This element in the code was implemented especially to work in tandem with other volume considerations. I Initially had issues making sure that the data being retrieved was correct, so adding an explicit check on the levels retrieved was necessary to ensure this.

### Performance Reporting and Analysis

The implementation includes comprehensive reporting capabilities:

```rust
fn export_session_report(&self) -> Result<(), Box<dyn Error>> {
    let filename = format!("trading_report_{}.csv", 
        Utc::now().format("%Y%m%d_%H%M%S"));
    let mut wtr = Writer::from_path(&filename)?;
```

The reporting system captures detailed trading statistics, risk metrics, and performance data, enabling thorough analysis of trading activities.

### Capital Preservation Mechanisms

As emphasised in the assignment specific measures have been taken in the code to preserve capital:

1. Stop-loss mechanisms to limit potential losses
2. Position size limits to prevent overexposure
3. Continuous monitoring of unrealized P&L
4. Market impact assessment before trade execution

# Critical Error Handling Code Snippets in Live Trading System

## Position Verification Error Handling

The system verifies position accuracy after every trade:

```rust
fn verify_position(&self, expected_size: f64, expected_value: f64, tolerance: f64) -> bool {
    let size_diff = (self.size - expected_size).abs();
    let value_diff = (self.value_in_usdt - expected_value).abs();
    size_diff < tolerance && value_diff < (expected_value * tolerance)
}
```

## Market Data Validation

Validates incoming market data to prevent trading on incorrect prices:

```rust
struct MarketDataValidator {
    last_price: f64,
    max_price_change: f64,
    min_volume: f64,
    price_history: VecDeque<f64>,
    volume_history: VecDeque<f64>,
    last_depth_print: SystemTime,
}
```

## Order Execution Error Handling

Handles failed orders and execution discrepancies:

```rust
if response.status().is_success() {
    state.record_trade(side.to_string(), quantity, price, config.symbol.clone());
    state.update_position(side, quantity, price);
    state.last_order_time = SystemTime::now();
} else {
    let error_text = response.text().await?;
    Err(format!("Order failed: {}", error_text).into())
}
```

## WebSocket Reconnection Logic

Handles connection drops with exponential backoff:

```rust
if state.error_count > 3 {
    return Err(e.into());
}
println!("Reconnecting in {} seconds...", config.reconnect_delay);
tokio::time::sleep(Duration::from_secs(config.reconnect_delay)).await;
```

## Risk Metric Error Detection

Monitors and updates risk metrics, catching potential issues:

```rust
fn update_risk_metrics(&mut self, price: f64, trade_value: f64) {
    if self.trades.len() > 1 {
        let last_trade = &self.trades[self.trades.len() - 2];
        let return_value = (price - last_trade.price) / last_trade.price;
        self.risk_metrics.update_var(return_value);
    }
}
```

## Market Impact Verification

Checks for sufficient liquidity before executing trades:

```rust
if let Some(weighted_ask) = market.get_execution_price("BUY", buy_quantity) {
    if weighted_ask < bb_lower && buy_quantity > 0.0 {
        state.execute_trade(client, config, "BUY", weighted_ask, buy_quantity).await?;
    }
} else {
    println!("Warning: Insufficient market depth for full buy order size");
}
```

## System Shutdown Error Handling

Manages graceful system shutdown with position closure:

```rust
match interrupt_result {
    Ok(()) => {
        println!("\nShutdown signal received, closing positions...");
        if state.position.size != 0.0 {
            if let Some(market) = &current_market_price {
                let side = if state.position.size > 0.0 { "SELL" } else { "BUY" };
                if let Some(exec_price) = market.get_execution_price(side, state.position.size.abs())
                {
                    if let Err(e) = place_order(client, config, side, exec_price, state, 
                        state.position.size.abs()).await {
                        eprintln!("Error closing positions: {}", e);
                    }
                }
            }
        }
        break;
    },
    Err(e) => eprintln!("Error handling Ctrl+C: {}", e),
}
```

# Main Trading Loop Deepdive

The system implements two distinct trading strategies: Bollinger Bands for mean reversion and Moving Average Crossover for trend following. 
## Main Trading Loop Architecture

The main trading loop operates as an event-driven system, processing multiple types of market data in real-time as shown in the earlier part of the report. 
```rust
async fn run_trading_loop(
    client: &Client,
    config: &TradingConfig,
    state: &mut TradingState,
) -> Result<(), Box<dyn Error>> {
```

This function serves as the central nervous system of our trading platform. It manages three critical streams of data:
- Candlestick data (klines) for strategy calculations
- Order book updates for execution pricing
- Trade data for market impact analysis

### Data Processing Pipeline

Each market data update flows through several stages:

1. Initial Reception and Validation
```rust
match serde_json::from_str::<CombinedStream<KlineData>>(&msg_str) {
    Ok(combined) => {
        if kline_data.kline.start > last_candle_time {
            if let Ok(close_price) = kline_data.kline.close.parse::<f64>() {
                price_window.add(close_price, config);
            }
        }
    }
}
```

This stage ensures data quality and proper sequencing, preventing strategy calculations based on stale or invalid data.

## Bollinger Bands Strategy Implementation

### Price Window Management
```rust
struct PriceWindow {
    prices: Vec<f64>,
    window_size: usize,
    short_ma: Vec<f64>,
    long_ma: Vec<f64>,
}
```

The PriceWindow structure maintains a rolling window of prices used for calculating Bollinger Bands. The implementation includes:

1. Moving Average Calculation
```rust
let sma: f64 = self.prices.iter().sum::<f64>() / self.prices.len() as f64;
```

2. Standard Deviation Computation
```rust
let variance = self.prices.iter()
    .map(|price| {
        let diff = price - sma;
        diff * diff
    })
    .sum::<f64>() / self.prices.len() as f64;
let std_dev = variance.sqrt();
```

3. Band Calculation - the standard deviation parameter is decided from the back test done in Python in Part 1
```rust
let upper_band = sma + (2.0 * std_dev);
let lower_band = sma - (2.0 * std_dev);
```

### Trading Signal Generation

The strategy generates signals based on price movements relative to the bands:

1. Buy Signals
When price drops below the lower band and meets liquidity requirements:
```rust
if market.ask < bb_lower && 
   state.can_add_position(config, market.ask) && 
   state.position.can_trade() {
    let buy_quantity = config.quantity.min(
        state.available_position(config, market.ask));
    
    if let Some(weighted_ask) = market.get_execution_price("BUY", buy_quantity) {
        if weighted_ask < bb_lower && buy_quantity > 0.0 {
            state.execute_trade(client, config, "BUY", weighted_ask, buy_quantity).await?;
        }
    }
}
```

2. Sell Signals
When price rises above the upper band and position exists:
```rust
if market.bid > bb_upper && state.position.size > 0.0 {
    if let Some(weighted_bid) = market.get_execution_price("SELL", state.position.size) {
        let potential_profit = weighted_bid - state.position.average_price;
        if potential_profit > min_profitable_move {
            state.execute_trade(client, config, "SELL", weighted_bid, state.position.size).await?;
        }
    }
}
```

## Moving Average Crossover Strategy

### Moving Average Calculation
```rust
impl PriceWindow {
    fn add(&mut self, price: f64, config: &TradingConfig) {
        if config.strategy == "ma_crossover" {
            // Calculate short MA
            if self.prices.len() >= config.ma_short_period {
                let short_window = &self.prices[self.prices.len() - config.ma_short_period..];
                let short_ma = short_window.iter().sum::<f64>() / config.ma_short_period as f64;
                self.short_ma.push(short_ma);
            }
            
            // Calculate long MA
            if self.prices.len() >= config.ma_long_period {
                let long_window = &self.prices[self.prices.len() - config.ma_long_period..];
                let long_ma = long_window.iter().sum::<f64>() / config.ma_long_period as f64;
                self.long_ma.push(long_ma);
            }
        }
    }
}
```

### Crossover Detection

The strategy monitors for crossovers between the moving averages:
```rust
fn detect_crossover(&self) -> Option<String> {
    if self.short_ma.len() >= 2 && self.long_ma.len() >= 2 {
        let prev_short = self.short_ma[self.short_ma.len() - 2];
        let prev_long = self.long_ma[self.long_ma.len() - 2];
        let curr_short = self.short_ma[self.short_ma.len() - 1];
        let curr_long = self.long_ma[self.long_ma.len() - 1];

        // Detect crossover up (bullish)
        if prev_short <= prev_long && curr_short > curr_long {
            return Some("BUY".to_string());
        }
        // Detect crossover down (bearish)
        else if prev_short >= prev_long && curr_short < curr_long {
            return Some("SELL".to_string());
        }
    }
    None
}
```

## Risk Management Integration

Both strategies incorporate risk management:

1. Minimum Profitable Move Calculation - Binance has 0.1% fees for trades, therefore trades that would return less than 0.2% would actually lead to losses net of fees. This snippet avoids that scenario.
```rust
let min_profitable_move = calculate_min_profitable_move(
    market.mid, config.quantity, 0.001
);
```

2. Position Size Management - given our trade could go through different levels we have to check where there is enough depth and the expected execution price we are going to get
```rust
let buy_quantity = config.quantity.min(
    state.available_position(config, market.ask));
```

3. Market Impact Assessment
```rust
if let Some(weighted_ask) = market.get_execution_price("BUY", buy_quantity) {
    // Execute only if price impact is acceptable
}
```

## Performance Monitoring and Reporting

The loop includes comprehensive performance tracking:

```rust
println!("\n=== Trading Status ===");
println!("Price: ${:.2} (Spread: ${:.2})", 
    market.mid,
    market.ask - market.bid
);
println!("Position: {:.8} BTC @ ${:.2}", 
    state.position.size,
    state.position.average_price
);
```

This monitoring helps in real-time strategy assessment and risk management especially in triggering the stoploss mechanisms.

This architecture provides a solid foundation for  trading operations across any Binance trading pair and over different parameters with a simple change of a few variables.

## Potential improvements

I am aware that there are further improvements that can be made, but this would add complexity in execution to a strategy that is fundamentally not very sophisticated. It would be like giving a novice driver the keys to a supercar - he might be able to drive it, but ultimately he wont be able to benefit from the full performance. Nevertheless, in order to improve execution and reduce risks associated with systematic trading I would also look to implement the following:

1. FIX Protocol Integration
   - Adding FIX protocol support would enhance compatibility with institutional trading systems
   - Would provide an alternative high-performance communication channel

2. Advanced Order Types
   - Implementation of limit orders and stop-limit orders - currently the system requests a market order
   - Addition of time-in-force parameters
   - More sophisticated execution algorithms for larger trades

3. Enhanced Risk Management
   - Implementation of more complex VaR calculations
   - Addition of correlation analysis for multi-asset trading
   - More sophisticated market impact models

## Conclusion

In conjunction with my full code this report covers the requirements set out by part 2 and 3 of the assignment. To summarise, the data submitted covered:

1. Comprehensive API integration with proper error handling
2. Risk management and monitoring
3. Detailed position tracking and verification
4. Real-time market data processing
5. Thorough performance reporting and analysis




