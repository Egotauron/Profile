use tokio; // Async runtime for Rust
use tokio_tungstenite::connect_async;  // WebSocket client implementation
use futures_util::StreamExt;  // Utilities for working with futures
use reqwest::Client; // HTTP client
use serde::{Deserialize, Serialize}; // Serialization/deserialization framework
use ta::indicators::BollingerBands; // Technical analysis library
use std::collections::VecDeque;
use ta::Next; 
use hmac::{Hmac, Mac}; // Cryptographic hashing
use sha2::Sha256;  // SHA-256 implementation
use std::time::{SystemTime, UNIX_EPOCH};
use std::error::Error;
use url::Url;
use chrono::{DateTime, Utc};
use std::env;
use csv::Writer;
use config::Config;
use tokio::signal::ctrl_c;
use std::time::Duration;
use std::io::Write;


// --- Configuration Structure ---
#[derive(Debug, Clone, Deserialize)]
struct TradingConfig {
    symbol: String,
    quantity: f64,
    max_position_value: f64,
    stop_loss_pct: f64,
    reconnect_delay: u64,
    api_key: String,
    api_secret: String,
    testnet: bool,
    depth_levels: usize,
    debug: bool,  // debug mode
    strategy: String,  // "bollinger" or "ma_crossover"
    ma_short_period: usize,  // Default to 5 minutes
    ma_long_period: usize,   // Default to 10 minutes
}

#[derive(Debug, Clone)]
enum TradingStrategy {
    Bollinger,
    MACrossover,
}

// Helper function for conditional debug printing
fn debug_print(config: &TradingConfig, msg: &str) {
    if config.debug {
        println!("[DEBUG] {}", msg);
    }
}

// --- Trade Record Structure ---
#[derive(Debug)]
struct Trade {
    timestamp: DateTime<Utc>,
    symbol: String,
    side: String,
    quantity: f64,
    price: f64,
    profit_loss: f64,
    fees: f64,
}


// --- Position Tracking Structure ---
#[derive(Debug)]
struct Position {
    size: f64,                    // Current position size in base currency (e.g., BTC)
    average_price: f64,           // Average entry price in USDT
    last_update: SystemTime,      // Last time position was updated
    value_in_usdt: f64,          // Position value in USDT
    unrealized_pnl: f64,         // Unrealized profit/loss in USDT
    last_trade_time: SystemTime,  // Time of last trade for cooldown
}

impl Position {
    fn new() -> Self {
        Self {
            size: 0.0,
            average_price: 0.0,
            last_update: SystemTime::now(),
            value_in_usdt: 0.0,
            unrealized_pnl: 0.0,
            last_trade_time: SystemTime::now(),
        }
    }

    fn verify_position(&self, expected_size: f64, expected_value: f64, tolerance: f64) -> bool {
        let size_diff = (self.size - expected_size).abs();
        let value_diff = (self.value_in_usdt - expected_value).abs();
        
        size_diff < tolerance && value_diff < (expected_value * tolerance)
    }

    fn update_unrealized_pnl(&mut self, current_price: f64) {
        if self.size != 0.0 {
            let current_value = self.size * current_price;
            let cost_basis = self.size * self.average_price;
            self.unrealized_pnl = current_value - cost_basis;
            self.value_in_usdt = current_value;
        } else {
            self.unrealized_pnl = 0.0;
            self.value_in_usdt = 0.0;
        }
    }

    fn can_trade(&self) -> bool {
        SystemTime::now()
            .duration_since(self.last_trade_time)
            .map(|duration| duration.as_secs() >= 300) // 5 minutes = 300 seconds
            .unwrap_or(false)
    }

    fn update_position(&mut self, side: &str, quantity: f64, price: f64) {
        match side {
            "BUY" => {
                let new_size = self.size + quantity;
                if new_size != 0.0 {
                    self.average_price = 
                        (self.size * self.average_price + quantity * price) / new_size;
                }
                self.size = new_size;
                self.last_trade_time = SystemTime::now(); // Only update timing for buys
            },
            "SELL" => {
                let new_size = self.size - quantity;
                if new_size != 0.0 {
                    self.average_price = 
                        (self.size * self.average_price - quantity * price) / new_size;
                }
                self.size = new_size;
            },
            _ => {}
        }
        self.last_update = SystemTime::now();
        self.update_unrealized_pnl(price);
    }
}


// Helper function to parse WebSocket messages with detailed error reporting
fn parse_ws_message(msg_str: &str, config: &TradingConfig) -> Result<(), Box<dyn Error>> {
    // Try parsing as depth data first
    match serde_json::from_str::<CombinedStream<DepthStreamData>>(msg_str) {
        Ok(combined) => {
            debug_print(config, "Successfully parsed depth data");
            // Process depth data...
            return Ok(());
        }
        Err(e) => debug_print(config, &format!("Not depth data: {}", e)),
    }

    // Try parsing as book ticker
    match serde_json::from_str::<CombinedStream<BookTickerData>>(msg_str) {
        Ok(combined) => {
            debug_print(config, "Successfully parsed book ticker");
            // Process book ticker...
            return Ok(());
        }
        Err(e) => debug_print(config, &format!("Not book ticker: {}", e)),
    }

    // Try parsing as kline data
    match serde_json::from_str::<CombinedStream<KlineData>>(msg_str) {
        Ok(combined) => {
            debug_print(config, "Successfully parsed kline data");
            // Process kline data...
            return Ok(());
        }
        Err(e) => {
            debug_print(config, &format!("Not kline data: {}", e));
            return Err("Failed to parse message as any known type".into());
        }
    }
}



// --- Risk Management Data Structures ---

#[derive(Debug)]
struct RiskMetrics {
    var_window: Vec<f64>,          // Store returns for VaR calculation
    var_window_size: usize,        // Size of VaR window (e.g., 20 periods)
    max_drawdown: f64,             // Maximum drawdown seen
    peak_value: f64,               // Highest portfolio value
    current_drawdown: f64,         // Current drawdown
    turnover: f64,                 // Trading turnover
    last_var: f64,                 // Last calculated VaR
}

impl RiskMetrics {
    fn new(window_size: usize) -> Self {
        Self {
            var_window: Vec::with_capacity(window_size),
            var_window_size: window_size,
            max_drawdown: 0.0,
            peak_value: 0.0,
            current_drawdown: 0.0,
            turnover: 0.0,
            last_var: 0.0,
        }
    }

    // Calculate Value at Risk using historical simulation
    fn update_var(&mut self, return_value: f64) {
        self.var_window.push(return_value);
        if self.var_window.len() > self.var_window_size {
            self.var_window.remove(0);
        }

        if self.var_window.len() >= 10 {  // Minimum sample size
            let mut returns = self.var_window.clone();
            returns.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
            let index = (returns.len() as f64 * 0.05) as usize;  // 95% VaR
            self.last_var = -returns[index];
        }
    }

    // Update drawdown calculations
    fn update_drawdown(&mut self, current_value: f64) {
        if current_value > self.peak_value {
            self.peak_value = current_value;
        }
        self.current_drawdown = (self.peak_value - current_value) / self.peak_value;
        self.max_drawdown = self.max_drawdown.max(self.current_drawdown);
    }

    // Update turnover when a trade occurs
    fn update_turnover(&mut self, trade_value: f64) {
        self.turnover += trade_value;
    }
}


// --- Market Data Validation ---
struct MarketDataValidator {
    last_price: f64,
    max_price_change: f64,  // Maximum allowed price change between updates
    min_volume: f64,        // Minimum required volume
    price_history: VecDeque<f64>,
    volume_history: VecDeque<f64>,
    last_depth_print: SystemTime,  // Add this field
}
impl MarketDataValidator {
    pub fn new(max_price_change: f64, min_volume: f64) -> Self {
        Self {
            last_price: 0.0,
            max_price_change,
            min_volume,
            price_history: VecDeque::with_capacity(100),
            volume_history: VecDeque::with_capacity(100),
            last_depth_print: SystemTime::now(),  // Initialize it
        }
    }

    fn validate_price(&mut self, market: &MarketPrice) -> Result<(), String> {
        // Check for stale data
        if let Ok(age) = SystemTime::now().duration_since(market.last_update) {
            if age.as_secs() > 5 {  // Data shouldn't be more than 5 seconds old
                return Err(format!("Market data is stale: {} seconds old", age.as_secs()));
            }
        }

        // Validate price movement
        if self.last_price > 0.0 {
            let price_change_pct = (market.mid - self.last_price).abs() / self.last_price;
            if price_change_pct > self.max_price_change {
                return Err(format!("Excessive price movement: {:.2}%", price_change_pct * 100.0));
            }
        }

        // Validate liquidity
        let min_volume = self.min_volume;
        
        // Check if we're using depth data or just top of book
        let (bid_volume, ask_volume) = if !market.depth_bids.is_empty() && !market.depth_asks.is_empty() {
            // Only print depth message if 5 seconds have elapsed since last print
            if let Ok(elapsed) = self.last_depth_print.elapsed() {
                if elapsed.as_secs() >= 5 {
                    println!("Using full depth data - Bid levels: {}, Ask levels: {}", 
                        market.depth_bids.len(), 
                        market.depth_asks.len()
                    );
                    self.last_depth_print = SystemTime::now();
                }
            }
            (market.total_bid_liquidity, market.total_ask_liquidity)
        } else {
            println!("No depth data available, using top of book");
            (market.bid_quantity, market.ask_quantity)
        };

        // Log detailed liquidity analysis if levels are low
        if bid_volume < self.min_volume || ask_volume < self.min_volume {
            println!("\nLiquidity Analysis at ${:.2}:", market.mid);
            println!("Bid Side:");
            println!("  Total Volume: {:.8} BTC", bid_volume);
            println!("  Best Bid: ${:.2} x {:.8}", market.bid, market.bid_quantity);
            if !market.depth_bids.is_empty() {
                println!("  Depth Levels: {}", market.depth_bids.len());
                println!("  Max Single Level: {:.8} BTC", 
                    market.depth_bids.iter()
                        .map(|(_, qty)| qty)
                        .fold(0.0f64, |max_so_far, &current| f64::max(max_so_far, current)));
            }
            
            println!("Ask Side:");
            println!("  Total Volume: {:.8} BTC", ask_volume);
            println!("  Best Ask: ${:.2} x {:.8}", market.ask, market.ask_quantity);
            if !market.depth_asks.is_empty() {
                println!("  Depth Levels: {}", market.depth_asks.len());
                println!("  Max Single Level: {:.8} BTC", 
                    market.depth_asks.iter()
                        .map(|(_, qty)| qty)
                        .fold(0.0f64, |max_so_far, &current| f64::max(max_so_far, current)));
            }
            
            // Determine the type of liquidity issue
            if market.depth_bids.is_empty() && market.depth_asks.is_empty() {
                println!("⚠️ Warning: Operating with top of book only - no depth data available");
            } else if bid_volume < self.min_volume && ask_volume < self.min_volume {
                println!("⚠️ Warning: Low liquidity on both sides of the book");
                println!("    Required: {:.8} BTC", self.min_volume);
            } else {
                println!("⚠️ Warning: Imbalanced liquidity - may affect execution quality");
            }
        }

        // Update historical tracking
        self.last_price = market.mid;
        self.price_history.push_back(market.mid);
        if self.price_history.len() > 100 {
            self.price_history.pop_front();
        }

        Ok(())
        
    }

    // Detect potential price manipulation
    fn detect_manipulation(&self) -> bool {
        if self.price_history.len() < 10 {
            return false;
        }

        // Check for sudden volume spikes with price changes
        let avg_volume: f64 = self.volume_history.iter().sum::<f64>() / self.volume_history.len() as f64;
        let recent_volume = self.volume_history.back().unwrap();
        
        *recent_volume > avg_volume * 3.0  // Volume spike detection
    }
}

// --- Binance WebSocket Data Structures ---

#[derive(Debug, Deserialize)]
struct CombinedStream<T> {
    stream: String,
    data: T,
}

#[derive(Debug, Serialize)]
struct OrderRequest {
    symbol: String,
    side: String,
    #[serde(rename = "type")]
    order_type: String,
    quantity: String,
    timestamp: u64,
    // Optional fields that might be needed for different order types
    #[serde(skip_serializing_if = "Option::is_none")]
    price: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    stop_price: Option<String>,
}

struct OrderBookStats {
    total_bid_liquidity: f64,
    total_ask_liquidity: f64,
    bid_levels: usize,
    ask_levels: usize,
    weighted_bid: f64,  // Volume-weighted average bid
    weighted_ask: f64,  // Volume-weighted average ask
    bid_depth_imbalance: f64,  // Ratio of bid to ask liquidity
}



#[derive(Debug, Deserialize)]
struct DepthStreamData {
    #[serde(rename = "lastUpdateId")]
    last_update_id: u64,
    bids: Vec<[String; 2]>,
    asks: Vec<[String; 2]>,
}

#[derive(Debug, Deserialize)]
#[serde(untagged)]
enum StringOrInt {
    String(String),
    Int(i64),
}

impl StringOrInt {
    fn as_i64(&self) -> i64 {
        match self {
            StringOrInt::String(s) => s.parse().unwrap_or(0),
            StringOrInt::Int(i) => *i,
        }
    }

    fn as_string(&self) -> String {
        match self {
            StringOrInt::String(s) => s.clone(),
            StringOrInt::Int(i) => i.to_string(),
        }
    }
}

#[derive(Debug, Deserialize)]
struct KlineData {
    #[serde(rename = "e")]
    event_type: String,

    #[serde(rename = "E")]
    event_time: StringOrInt,

    #[serde(rename = "s")]
    symbol: String,

    #[serde(rename = "k")]
    kline: Kline
}

#[derive(Debug, Deserialize)]
struct HistoricalKline {
    #[serde(rename = "0")]
    open_time: StringOrInt,
    #[serde(rename = "1")]
    open: String,
    #[serde(rename = "2")]
    high: String,
    #[serde(rename = "3")]
    low: String,
    #[serde(rename = "4")]
    close: String,
    #[serde(rename = "5")]
    volume: String,
    #[serde(rename = "6")]
    close_time: StringOrInt,
    #[serde(rename = "7")]
    quote_volume: String,
    #[serde(rename = "8")]
    trades: StringOrInt,
    #[serde(rename = "9")]
    taker_base: String,
    #[serde(rename = "10")]
    taker_quote: String,
}

#[derive(Debug, Deserialize)]
struct Kline {
    #[serde(rename = "t")]
    start: StringOrInt,
    
    #[serde(rename = "T")]
    end: StringOrInt,
    
    #[serde(rename = "s")]
    symbol: String,
    
    #[serde(rename = "i")]
    interval: String,
    
    #[serde(rename = "f")]
    first_trade_id: StringOrInt,
    
    #[serde(rename = "L")]
    last_trade_id: StringOrInt,
    
    #[serde(rename = "o")]
    open: String,
    
    #[serde(rename = "c")]
    close: String,
    
    #[serde(rename = "h")]
    high: String,
    
    #[serde(rename = "l")]
    low: String,
    
    #[serde(rename = "v")]
    volume: String,
    
    #[serde(rename = "n")]
    number_of_trades: StringOrInt,
    
    #[serde(rename = "x")]
    is_closed: bool,
}


// Add a new structure for bid/ask

#[derive(Debug, Deserialize)]
struct BookTickerData {
    #[serde(rename = "u")]
    update_id: u64,
    #[serde(rename = "s")]
    symbol: String,
    #[serde(rename = "b")]
    bid_price: String,
    #[serde(rename = "B")]
    bid_quantity: String,
    #[serde(rename = "a")]
    ask_price: String,
    #[serde(rename = "A")]
    ask_quantity: String,
}

#[derive(Debug, Clone)]
struct MarketPrice {
    bid: f64,
    ask: f64,
    bid_quantity: f64,
    ask_quantity: f64,
    mid: f64,
    depth_bids: Vec<(f64, f64)>,  // (price, quantity) pairs
    depth_asks: Vec<(f64, f64)>,  // (price, quantity) pairs
    total_bid_liquidity: f64,     // Track total liquidity for quick access
    total_ask_liquidity: f64,
    last_update: SystemTime,
}

// Structure to track order book metrics for analysis and debugging
struct OrderBookMetrics {
    bid_liquidity: f64,    // Total volume available on bid side
    ask_liquidity: f64,    // Total volume available on ask side
    bid_levels_used: usize,// Number of bid price levels utilized
    ask_levels_used: usize,// Number of ask price levels utilized
    slippage: f64,        // Price impact from walking the book
}

impl MarketPrice {
    /// Creates a new MarketPrice instance from top-of-book data
    /// This is the primary constructor used when receiving book ticker updates
    fn from_book_ticker(data: &BookTickerData) -> Result<Self, Box<dyn Error>> {
        // Parse all string fields into f64 with detailed error handling
        let bid = data.bid_price.parse::<f64>().map_err(|e| 
            format!("Failed to parse top bid price: {}", e))?;
        let ask = data.ask_price.parse::<f64>().map_err(|e| 
            format!("Failed to parse top ask price: {}", e))?;
        let bid_quantity = data.bid_quantity.parse::<f64>().map_err(|e| 
            format!("Failed to parse top bid quantity: {}", e))?;
        let ask_quantity = data.ask_quantity.parse::<f64>().map_err(|e| 
            format!("Failed to parse top ask quantity: {}", e))?;
        
        // Validate price values to ensure market integrity
        if bid <= 0.0 || ask <= 0.0 || bid >= ask {
            return Err("Invalid top-of-book prices".into());
        }
        
        let mid = (bid + ask) / 2.0;
 
        // Initialize all fields with appropriate starting values
        Ok(Self {
            bid,
            ask,
            bid_quantity,
            ask_quantity,
            mid,
            depth_bids: Vec::new(),        // Empty vectors for depth data
            depth_asks: Vec::new(),
            total_bid_liquidity: bid_quantity,  // Start with top-of-book liquidity
            total_ask_liquidity: ask_quantity,
            last_update: SystemTime::now(),     // Track data freshness
        })
    }

    /// Updates the order book depth data using pre-parsed arrays of price/quantity pairs
    /// This is typically used after processing raw depth messages
    fn update_depth_from_arrays(
        &mut self, 
        bids: &[(f64, f64)], 
        asks: &[(f64, f64)]
    ) -> Result<(), Box<dyn Error>> {
        // Sort bids highest to lowest
        let mut sorted_bids = bids.to_vec();
        sorted_bids.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
        
        // Sort asks lowest to highest
        let mut sorted_asks = asks.to_vec();
        sorted_asks.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
        
        // Ensure the book isn't crossed (highest bid < lowest ask)
        if let (Some(highest_bid), Some(lowest_ask)) = (sorted_bids.first(), sorted_asks.first()) {
            if highest_bid.0 >= lowest_ask.0 {
                return Err("Invalid order book: crossed bid/ask".into());
            }
        }

        // Update total liquidity metrics for both sides
        self.total_bid_liquidity = sorted_bids.iter().map(|(_, qty)| qty).sum();
        self.total_ask_liquidity = sorted_asks.iter().map(|(_, qty)| qty).sum();
        
        // Update depth arrays and timestamp
        self.depth_bids = sorted_bids;
        self.depth_asks = sorted_asks;
        self.last_update = SystemTime::now();

        Ok(())
    }
    
    /// Processes a raw depth stream data message and updates the order book
    /// This handles the parsing of string values from the WebSocket feed
    fn update_depth(&mut self, depth_data: &DepthStreamData) -> Result<(), Box<dyn Error>> {
        self.depth_bids.clear();
        self.depth_asks.clear();
        
        let mut total_bid_volume = 0.0;
        let mut total_ask_volume = 0.0;
        
        // Process bid side
        for [price_str, qty_str] in &depth_data.bids {
            let price = price_str.parse::<f64>().map_err(|e| 
                format!("Failed to parse bid price: {}", e))?;
            let qty = qty_str.parse::<f64>().map_err(|e| 
                format!("Failed to parse bid quantity: {}", e))?;
            
            if price <= 0.0 || qty <= 0.0 {
                return Err("Invalid bid price or quantity".into());
            }
            
            total_bid_volume += qty;
            self.depth_bids.push((price, qty));
        }
 
        // Process ask side
        if depth_data.asks.is_empty() {
            println!("WARNING: Received empty ask depth data");
        } else {
            for [price_str, qty_str] in &depth_data.asks {
                let price = price_str.parse::<f64>().map_err(|e| 
                    format!("Failed to parse ask price: {}", e))?;
                let qty = qty_str.parse::<f64>().map_err(|e| 
                    format!("Failed to parse ask quantity: {}", e))?;
                
                if price <= 0.0 || qty <= 0.0 {
                    return Err("Invalid ask price or quantity".into());
                }
                
                total_ask_volume += qty;
                self.depth_asks.push((price, qty));
            }
        }
 
        // Sort both sides for efficient price discovery
        self.depth_bids.sort_by(|a, b| b.0.partial_cmp(&a.0)
            .unwrap_or(std::cmp::Ordering::Equal));
        self.depth_asks.sort_by(|a, b| a.0.partial_cmp(&b.0)
            .unwrap_or(std::cmp::Ordering::Equal));
 
        // Update total liquidity metrics
        self.total_bid_liquidity = total_bid_volume;
        self.total_ask_liquidity = total_ask_volume;
        
        println!("Order Book Update:");
        println!("  Bid Levels: {} (Volume: {:.8})", self.depth_bids.len(), total_bid_volume);
        println!("  Ask Levels: {} (Volume: {:.8})", self.depth_asks.len(), total_ask_volume);
 
        self.last_update = SystemTime::now();
        Ok(())
    }
 
    /// High-level interface for getting execution price estimates
    /// Takes into account full order book depth for accurate pricing
    fn get_execution_price(&self, side: &str, size: f64) -> Option<f64> {
        let available_liquidity = match side {
            "BUY" => self.total_ask_liquidity,
            "SELL" => self.total_bid_liquidity,
            _ => return None,
        };

        // Early liquidity check
        if available_liquidity < size {
            println!("Warning: Insufficient liquidity for {} {:.8} BTC", 
                side, size);
            println!("Available: {:.8} BTC", available_liquidity);
            return None;
        }

        // Get the appropriate side of the book
        let levels = match side {
            "BUY" => &self.depth_asks,
            "SELL" => &self.depth_bids,
            _ => return None,
        };

        if levels.is_empty() {
            return None;
        }

        // Walk the order book to calculate weighted average price
        let mut remaining_size = size;
        let mut total_cost = 0.0;
        
        for &(price, quantity) in levels {
            let fill_size = remaining_size.min(quantity);
            total_cost += fill_size * price;
            remaining_size -= fill_size;
            
            if remaining_size <= 0.0 {
                break;
            }
        }

        Some(total_cost / size)
    }

    /// Analyzes market liquidity for a potential trade
    /// Provides detailed metrics about available liquidity and potential market impact
    fn analyze_liquidity(&self, size: f64, side: &str) -> Option<LiquidityMetrics> {
        let levels = match side {
            "BUY" => &self.depth_asks,
            "SELL" => &self.depth_bids,
            _ => return None,
        };
 
        if levels.is_empty() {
            return None;
        }
 
        let mut remaining_size = size;
        let mut levels_needed = 0;
        let mut total_available = 0.0;
        let reference_price = levels[0].0;
        let mut worst_price = reference_price;
        
        // Calculate liquidity metrics
        for (price, quantity) in levels {
            total_available += quantity;
            
            if remaining_size > 0.0 {
                levels_needed += 1;
                worst_price = *price;
                remaining_size -= quantity.min(remaining_size);
            }
        }
 
        let metrics = LiquidityMetrics {
            total_available,
            levels_needed,
            top_level_volume: levels[0].1,
            price_impact: match side {
                "BUY" => worst_price - reference_price,
                "SELL" => reference_price - worst_price,
                _ => 0.0,
            },
            average_level_volume: total_available / levels.len() as f64,
        };
 
        // Log liquidity analysis
        println!("\nDetailed Liquidity Analysis for {} {:.8}:", side, size);
        println!("  Available at Best Price: {:.8} ({:.1}% of order)",
            metrics.top_level_volume,
            (metrics.top_level_volume / size * 100.0).min(100.0));
        println!("  Total Available Liquidity: {:.8}", metrics.total_available);
        println!("  Price Levels Needed: {}/{}", metrics.levels_needed, levels.len());
        println!("  Price Impact: {:.2}", metrics.price_impact);
        println!("  Average Level Volume: {:.8}", metrics.average_level_volume);
        
        // Warn about potential liquidity issues
        if metrics.top_level_volume < size * 0.2 {
            println!("⚠️ Warning: Less than 20% of order size available at best price");
        }
        if metrics.levels_needed > levels.len() / 2 {
            println!("⚠️ Warning: Order requires more than 50% of available price levels");
        }
        if metrics.price_impact > reference_price * 0.001 {
            println!("⚠️ Warning: Significant price impact detected");
        }
 
        Some(metrics)
    }
}
 
/// This new struct tracks various liquidity metrics for the order book
struct LiquidityMetrics {
total_available: f64,      // Total volume available across all levels
levels_needed: usize,      // Number of price levels needed to fill order
top_level_volume: f64,     // Volume available at best price
price_impact: f64,         // Price difference between best and worst level needed
average_level_volume: f64,  // Average volume per level
}

// --- Trading State Management ---

// Price window handler
struct PriceWindow {
    prices: Vec<f64>,
    window_size: usize,
    short_ma: Vec<f64>,
    long_ma: Vec<f64>,
    bb_upper: Option<f64>,    // Add these fields
    bb_middle: Option<f64>,   // to store BB values
    bb_lower: Option<f64>,
}

impl PriceWindow {

    fn new(window_size: usize) -> Self {
        Self {
            prices: Vec::with_capacity(window_size),
            window_size,
            short_ma: Vec::new(),
            long_ma: Vec::new(),
            bb_upper: None,
            bb_middle: None,
            bb_lower: None,
        }
    }

    // Add a method to get the current number of prices in the window
    fn len(&self) -> usize {
        self.prices.len()
    }

    // Add a method to check if the price window is empty
    fn is_empty(&self) -> bool {
        self.prices.is_empty()
    }

    // Add a method to get the configured window size
    fn window_size(&self) -> usize {
        self.window_size
    }

    fn add(&mut self, price: f64, config: &TradingConfig) {
        // Add price to main window, but only if it's significantly different
        let min_price_change = price * 0.0001; // 0.01% change threshold
        
        if let Some(last_price) = self.prices.last() {
            if (price - last_price).abs() < min_price_change {
                return; // Skip tiny price changes to avoid noise
            }
        }

        if self.prices.len() >= self.window_size {
            self.prices.remove(0);
        }
        self.prices.push(price);

        match config.strategy.as_str() {
            "ma_crossover" => {
                // Calculate short MA
                if self.prices.len() >= config.ma_short_period {
                    let short_window = &self.prices[self.prices.len() - config.ma_short_period..];
                    let short_ma = short_window.iter().sum::<f64>() / config.ma_short_period as f64;
                    if self.short_ma.len() >= self.window_size {
                        self.short_ma.remove(0);
                    }
                    self.short_ma.push(short_ma);
                }

                // Calculate long MA
                if self.prices.len() >= config.ma_long_period {
                    let long_window = &self.prices[self.prices.len() - config.ma_long_period..];
                    let long_ma = long_window.iter().sum::<f64>() / config.ma_long_period as f64;
                    if self.long_ma.len() >= self.window_size {
                        self.long_ma.remove(0);
                    }
                    self.long_ma.push(long_ma);
                }
            },
            "bollinger" => {
                if self.prices.len() >= 20 {  // Standard BB period
                    // Calculate moving average
                    let ma: f64 = self.prices.iter().sum::<f64>() / self.prices.len() as f64;
                    
                    // Calculate standard deviation
                    let variance = self.prices.iter()
                        .map(|price| {
                            let diff = price - ma;
                            diff * diff
                        })
                        .sum::<f64>() / self.prices.len() as f64;
                    let std_dev = variance.sqrt();
                    
                    // Store BB values (you might want to add these to the PriceWindow struct)
                    self.bb_middle = Some(ma);
                    self.bb_upper = Some(ma + (2.0 * std_dev));
                    self.bb_lower = Some(ma - (2.0 * std_dev));
                }
            },
            _ => {}
        }
    }

    fn calculate_bb(&self) -> Option<(f64, f64, f64)> {
        match (self.bb_lower, self.bb_middle, self.bb_upper) {
            (Some(lower), Some(middle), Some(upper)) => Some((lower, middle, upper)),
            _ => None
        }
    }

    // Add method to get latest moving averages
    fn get_latest_mas(&self) -> Option<(f64, f64)> {
        if !self.short_ma.is_empty() && !self.long_ma.is_empty() {
            Some((*self.short_ma.last().unwrap(), *self.long_ma.last().unwrap()))
        } else {
            None
        }
    }

    // Add method to detect crossover signals
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

    fn is_ready(&self, config: &TradingConfig) -> bool {
        match config.strategy.as_str() {
            "bollinger" => self.prices.len() >= self.window_size,
            "ma_crossover" => self.prices.len() >= config.ma_long_period,
            _ => false,
        }
    }

    // Calculates Bollinger Bands using a 20-period standard deviation
    fn calculate_bb(&self) -> Option<(f64, f64, f64)> {
        // We need enough data points to calculate meaningful bands
        if self.prices.len() < self.window_size {
            return None;
        }

        // Calculate Simple Moving Average (middle band)
        let sma: f64 = self.prices.iter().sum::<f64>() / self.prices.len() as f64;

        // Calculate Standard Deviation
        let variance = self.prices.iter()
            .map(|price| {
                let diff = price - sma;
                diff * diff
            })
            .sum::<f64>() / self.prices.len() as f64;
        
        let std_dev = variance.sqrt();
        
        // Calculate Bollinger Bands
        // Upper and lower bands are typically 2 standard deviations from the mean
        let upper_band = sma + (2.0 * std_dev);
        let lower_band = sma - (2.0 * std_dev);

        Some((lower_band, sma, upper_band))
    }
}


struct TradingState {
    position: Position,
    last_order_time: SystemTime,
    error_count: u32,
    cumulative_return: f64,
    total_trades: i32,
    profitable_trades: i32,
    trades: Vec<Trade>,
    session_start: DateTime<Utc>,
    risk_metrics: RiskMetrics,
    market_validator: MarketDataValidator,
}

impl TradingState {
    // Initialize a new trading state with default values and specified parameters
    fn new() -> Self {
        Self {
            position: Position::new(),          // Current trading position
            last_order_time: SystemTime::now(), // Track time of last order
            error_count: 0,                     // Counter for error tracking
            cumulative_return: 0.0,             // Total P&L across all trades
            total_trades: 0,                    // Count of all trades executed
            profitable_trades: 0,               // Count of profitable trades
            trades: Vec::new(),                 // History of all trades
            session_start: Utc::now(),          // Start time of trading session
            // Initialize risk metrics with a 5-period window for VaR calculation
            risk_metrics: RiskMetrics::new(5),  
            // Initialize market validator with 5% max price change tolerance
            market_validator: MarketDataValidator::new(0.05, 0.002),
        }
    }

    fn update_position(&mut self, side: &str, quantity: f64, price: f64) {
        self.position.update_position(side, quantity, price);
    }

    // Execute a trade with comprehensive pre and post-trade checks
    async fn execute_trade(
        &mut self,
        client: &Client,
        config: &TradingConfig,
        side: &str,
        price: f64,
        quantity: f64
    ) -> Result<(), Box<dyn Error>> {
        // Create a validation market structure for pre-trade checks
        // We simulate a worst-case scenario for validation by slightly adjusting prices
        let validation_market = MarketPrice {
            bid: if side == "SELL" { price } else { price * 0.999 },  // Adjust bid down for buys
            ask: if side == "BUY" { price } else { price * 1.001 },   // Adjust ask up for sells
            bid_quantity: quantity,
            ask_quantity: quantity,
            mid: price,
            depth_bids: vec![(price, quantity)],   // Simple depth representation
            depth_asks: vec![(price, quantity)],
            total_bid_liquidity: quantity,         // Set to order quantity for validation
            total_ask_liquidity: quantity,         // Set to order quantity for validation
            last_update: SystemTime::now(),        // Current timestamp for freshness check
        };
    
        // Perform pre-trade market validation
        if let Err(e) = self.market_validator.validate_price(&validation_market) {
            return Err(format!("Market validation failed: {}", e).into());
        }
    
        // Execute the order through the exchange
        place_order(client, config, side, price, self, quantity).await?;
    
        // Calculate and verify expected position after trade
        let expected_size = match side {
            "BUY" => self.position.size + quantity,
            "SELL" => self.position.size - quantity,
            _ => self.position.size,
        };
    
        // Verify position matches expectations within tolerance
        if !self.position.verify_position(expected_size, price * expected_size, 0.001) {
            eprintln!(
                "Position verification failed - expected size: {}, actual: {}", 
                expected_size, self.position.size
            );
        }
    
        // Update risk metrics with new trade data
        self.update_risk_metrics(price, price * quantity);
    
        Ok(())
    }

    // Calculate how much position can be added based on configuration limits
    fn available_position(&self, config: &TradingConfig, current_price: f64) -> f64 {
        let current_position_value = self.position.value_in_usdt;
        let remaining_value = config.max_position_value - current_position_value;
        (remaining_value / current_price).max(0.0)
    }

    // Get current position value in quote currency
    fn position_value(&self) -> f64 {
        self.position.value_in_usdt
    }

    // Check if additional position can be added within limits
    fn can_add_position(&self, config: &TradingConfig, price: f64) -> bool {
        let current_value = self.position_value();
        let new_value = current_value + (config.quantity * price);
        
        current_value < config.max_position_value && 
            new_value <= config.max_position_value
    }

    // Record a trade and update trading statistics
    fn record_trade(&mut self, side: String, quantity: f64, price: f64, symbol: String) {
        let fee_rate = 0.001; // 0.1% fee
        let fees = price * quantity * fee_rate;
        
        if side == "SELL" {
            // Calculate profit/loss for closing trades
            let profit = (price - self.position.average_price) * quantity;
            let total_fees = fees + (self.position.average_price * quantity * fee_rate);
            let net_profit = profit - total_fees;
            
            let trade = Trade {
                timestamp: Utc::now(),
                symbol,
                side,
                quantity,
                price,
                profit_loss: net_profit,
                fees: total_fees,
            };
            
            println!("Recording closing trade: Side=SELL, Entry=${:.2}, Exit=${:.2}, Raw P&L=${:.4}, Fees=${:.4}, Net P&L=${:.4}", 
                self.position.average_price, price, profit, total_fees, net_profit);
            
            self.trades.push(trade);
            self.cumulative_return += net_profit;
            self.total_trades += 1;
            if net_profit > 0.0 {
                self.profitable_trades += 1;
            }
        } else {
            // Record opening trades
            let trade = Trade {
                timestamp: Utc::now(),
                symbol,
                side,
                quantity,
                price,
                profit_loss: 0.0,
                fees,
            };
            
            println!("Recording opening trade: Side=BUY, Price=${:.2}", price);
            self.trades.push(trade);
        }
    }

    // Update risk metrics with latest market data
    fn update_risk_metrics(&mut self, price: f64, trade_value: f64) {
        // Calculate return if we have previous trades
        if self.trades.len() > 1 {
            let last_trade = &self.trades[self.trades.len() - 2];
            let return_value = (price - last_trade.price) / last_trade.price;
            self.risk_metrics.update_var(return_value);
        }

        // Update drawdown calculations
        let total_value = self.position_value() + self.cumulative_return;
        self.risk_metrics.update_drawdown(total_value);
        self.risk_metrics.update_turnover(trade_value);
    }

    // Export detailed trading session report to CSV
    fn export_session_report(&self) -> Result<(), Box<dyn Error>> {
        println!("Starting export... Number of trades: {}", self.trades.len());
        
        let filename = format!("trading_report_{}.csv", 
            Utc::now().format("%Y%m%d_%H%M%S"));
        let mut wtr = Writer::from_path(&filename)?;

        // Write session summary
        wtr.write_record(&["Session Summary", "", "", "", "", "", "", ""])?;
        wtr.write_record(&["Start Time", &self.session_start.to_string(), "", "", "", "", "", ""])?;
        wtr.write_record(&["End Time", &Utc::now().to_string(), "", "", "", "", "", ""])?;
        wtr.write_record(&["Total Trades", &self.total_trades.to_string(), "", "", "", "", "", ""])?;
        wtr.write_record(&["Profitable Trades", &self.profitable_trades.to_string(), "", "", "", "", "", ""])?;

        // Calculate and write win rate
        let win_rate = if self.total_trades > 0 {
            (self.profitable_trades as f64 / self.total_trades as f64) * 100.0
        } else {
            0.0
        };
        wtr.write_record(&["Win Rate", &format!("{:.2}%", win_rate), "", "", "", "", "", ""])?;
        wtr.write_record(&["Final P&L", &format!("${:.2}", self.cumulative_return), "", "", "", "", "", ""])?;

        // Add trade history if available
        if !self.trades.is_empty() {
            wtr.write_record(&["", "", "", "", "", "", "", ""])?;
            wtr.write_record(&["Trade History", "", "", "", "", "", "", ""])?;
            wtr.write_record(&[
                "Timestamp",
                "Symbol",
                "Side",
                "Quantity",
                "Price",
                "P&L",
                "Fees",
                "Running P&L"
            ])?;

            let mut running_pnl = 0.0;
            for trade in &self.trades {
                running_pnl += trade.profit_loss - trade.fees;
                wtr.write_record(&[
                    &trade.timestamp.to_string(),
                    &trade.symbol,
                    &trade.side,
                    &format!("{:.8}", trade.quantity),
                    &format!("${:.2}", trade.price),
                    &format!("${:.2}", trade.profit_loss),
                    &format!("${:.2}", trade.fees),
                    &format!("${:.2}", running_pnl)
                ])?;
            }
        }

        wtr.flush()?;
        println!("Report exported to: {}", filename);
        Ok(())
    }
}

fn load_trading_config() -> Result<TradingConfig, Box<dyn Error>> {
    let config = Config::builder()
        .add_source(config::File::with_name("config"))
        .add_source(config::Environment::with_prefix("APP"))
        .build()?;

    Ok(config.try_deserialize()?)
}


// --- Trading Operations ---

// Place an order on the exchange
async fn place_order(
    client: &Client,
    config: &TradingConfig,
    side: &str,
    price: f64,
    state: &mut TradingState,
    quantity: f64,  // Using passed quantity instead of config.quantity
) -> Result<(), Box<dyn Error>> {
    // Generate timestamp for API request
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)?
        .as_millis() as u64;

    // Use the actual quantity in the params string
    let params = format!(
        "symbol={}&side={}&type=MARKET&quantity={}&timestamp={}",
        config.symbol,
        side,
        quantity,  // Use passed quantity
        timestamp
    );

    // Generate HMAC signature for API authentication
    type HmacSha256 = Hmac<Sha256>;
    let mut mac = HmacSha256::new_from_slice(config.api_secret.as_bytes())
        .expect("HMAC can take key of any size");
    mac.update(params.as_bytes());
    let signature = hex::encode(mac.finalize().into_bytes());

    // Select appropriate WebSocket URL based on environment
    let base_url = if config.testnet { 
        "https://testnet.binance.vision"
    } else {
        "https://api.binance.com"
    };
    
    // Use the actual quantity in the query parameters
    let response = client
        .post(format!("{}/api/v3/order", base_url))
        .header("X-MBX-APIKEY", &config.api_key)
        .query(&[
            ("symbol", &config.symbol),
            ("side", &side.to_string()),
            ("type", &"MARKET".to_string()),
            ("quantity", &quantity.to_string()),  // Use passed quantity
            ("timestamp", &timestamp.to_string()),
            ("signature", &signature),
        ])
        .send()
        .await?;

    if response.status().is_success() {
        state.record_trade(side.to_string(), quantity, price, config.symbol.clone());
        state.update_position(side, quantity, price);
        state.last_order_time = SystemTime::now();
        
        println!(
            "Order executed: {} {} @ ${:.2} - Cumulative Return: ${:.2}",
            side,
            quantity,
            price,
            state.cumulative_return
        );
        Ok(())
    } else {
        let error_text = response.text().await?;
        Err(format!("Order failed: {}", error_text).into())
    }
}

// Check if stop loss should be triggered
fn should_stop_loss(current_price: f64, position: &Position, stop_loss_pct: f64) -> bool {
    if position.size == 0.0 {
        return false;
    }
    // Calculate percentage loss
    let loss_pct = if position.size > 0.0 {
        // For long positions
        (position.average_price - current_price) / position.average_price * 100.0
    } else {
        // For short positions
        (current_price - position.average_price) / position.average_price * 100.0
    };
    
    loss_pct > stop_loss_pct
}

// Calculate minimum price move needed to cover fees
fn calculate_min_profitable_move(price: f64, quantity: f64, fee_rate: f64) -> f64 {
    let position_value = price * quantity;
    let total_fees = position_value * fee_rate * 2.0;  // Multiply by 2 for round trip
    total_fees / quantity
}


async fn initialize_with_historical_data(
    client: &Client,
    config: &TradingConfig,
    price_window: &mut PriceWindow,
) -> Result<SystemTime, Box<dyn Error>> {
    println!("Fetching historical data for initialization...");
    
    let required_periods = match config.strategy.as_str() {
        "bollinger" => 20,
        "ma_crossover" => config.ma_long_period.max(20),
        _ => return Err("Invalid strategy specified".into()),
    };

    let klines_to_fetch = required_periods + 10;
    
    let base_url = if config.testnet {
        "https://testnet.binance.vision"
    } else {
        "https://api.binance.com"
    };

    let endpoint = format!("{}/api/v3/klines", base_url);
    let now = SystemTime::now();
    let timestamp = now.duration_since(UNIX_EPOCH)?.as_millis() as u64;
    let start_time = timestamp - (klines_to_fetch as u64 * 15 * 60 * 1000);

    let response = client
        .get(&endpoint)
        .query(&[
            ("symbol", &config.symbol),
            ("interval", &"15m".to_string()),
            ("limit", &klines_to_fetch.to_string()),
            ("startTime", &start_time.to_string()),
            ("endTime", &timestamp.to_string()),
        ])
        .send()
        .await?;

    if !response.status().is_success() {
        return Err(format!("API request failed: {}", response.status()).into());
    }

    // Parse response as raw JSON Value
    let klines: Vec<Vec<serde_json::Value>> = response.json().await?;
    println!("Received {} historical klines", klines.len());

    let mut last_timestamp = 0i64;
    for kline in klines {
        if kline.len() >= 6 {  // Make sure we have enough elements
            // Parse timestamp from the first element
            if let Some(timestamp) = kline[0].as_i64() {
                if timestamp > last_timestamp {
                    // Parse close price from the 4th element (index 4)
                    if let Some(close_str) = kline[4].as_str() {
                        if let Ok(close_price) = close_str.parse::<f64>() {
                            price_window.add(close_price, config);
                            last_timestamp = timestamp;
                        }
                    }
                }
            }
        }
    }

    println!("Historical data processed. Window size: {}/{}", 
        price_window.len(), price_window.window_size());

    Ok(now)
}


// Main trading loop implementation

async fn run_trading_loop(
    client: &Client,
    config: &TradingConfig,
    state: &mut TradingState,
) -> Result<(), Box<dyn Error>> {
    
    let mut status_tracker = StatusTracker::new(10);
    let mut last_print = SystemTime::now();

    // ===== Strategy Setup =====
    let strategy = match config.strategy.as_str() {
        "bollinger" => TradingStrategy::Bollinger,
        "ma_crossover" => TradingStrategy::MACrossover,
        _ => return Err("Invalid trading strategy specified".into()),
    };

    let window_size = match &strategy {
        TradingStrategy::Bollinger => 20,
        TradingStrategy::MACrossover => config.ma_long_period.max(20),
    };
    
    // Initialize price tracking window
    let mut price_window = PriceWindow::new(window_size);

    // Initialize with historical data
    let last_update = initialize_with_historical_data(client, config, &mut price_window).await?;
    let mut last_candle_time = last_update
        .duration_since(UNIX_EPOCH)?
        .as_millis() as i64;

    // Set indicators_initialized based on sufficient historical data
    let mut indicators_initialized = price_window.is_ready(config);
    if indicators_initialized {
        println!("Indicators initialized with historical data. Starting real-time trading...");
    } else {
        println!("Warning: Insufficient historical data, will continue collecting in real-time...");
    }

    // ===== WebSocket Setup =====
    let ws_base_url = if config.testnet {
        "wss://testnet.binance.vision/stream?streams="
    } else {
        "wss://stream.binance.com:9443/stream?streams="
    };
    
    let symbol_lower = config.symbol.to_lowercase();
    let streams = format!(
        "{}@kline_15m/{}@bookTicker/{}@depth{}@100ms",
        symbol_lower, symbol_lower, symbol_lower, config.depth_levels
    );
    
    let url_str = format!("{}{}", ws_base_url, streams);
    debug_print(config, &format!("Connecting to WebSocket URL: {}", url_str));

    // ===== Connection Establishment =====
    let url = url::Url::parse(&url_str)?;
    let (ws_stream, _) = tokio_tungstenite::connect_async(url).await?;
    let (_, mut read) = ws_stream.split();
    
    // ===== State Variables =====
    let mut current_bb_output: Option<(f64, f64, f64)> = None;
    let mut current_market_price: Option<MarketPrice> = None;
    let mut interrupt = Box::pin(ctrl_c());

    println!("Connected to WebSocket feed for {}", config.symbol);

    // ===== Main Event Loop =====
    loop {
        tokio::select! {
            // Handle shutdown
            result = &mut interrupt => {
                if let Ok(()) = result {
                    println!("\nShutdown signal received, closing positions...");
                    if let Some(market) = &current_market_price {
                        if state.position.size != 0.0 {
                            let side = if state.position.size > 0.0 { "SELL" } else { "BUY" };
                            if let Some(exec_price) = market.get_execution_price(
                                side, 
                                state.position.size.abs()
                            ) {
                                if let Err(e) = place_order(
                                    client, config, side, exec_price, state, 
                                    state.position.size.abs()
                                ).await {
                                    eprintln!("Error closing positions: {}", e);
                                }
                            }
                        }
                    }
                    break;
                }
            }

            // Handle WebSocket messages
            msg = read.next() => match msg {
                Some(Ok(msg)) => {
                    let msg_str = msg.to_string();
                    
                    // Process order book depth
                    if let Ok(combined) = serde_json::from_str::<CombinedStream<DepthStreamData>>(&msg_str) {
                        if let Some(market) = &mut current_market_price {
                            handle_depth_update(market, &combined.data, config)?;
                        }
                    }

                    // Process book ticker
                    if let Ok(combined) = serde_json::from_str::<CombinedStream<BookTickerData>>(&msg_str) {
                        if handle_book_ticker_update(
                            &mut current_market_price,
                            &combined.data,
                            &mut price_window,
                            &strategy,
                            &mut current_bb_output,
                            config
                        )? {
                            // Execute trading logic on each price update
                            if let Some(market) = &current_market_price {
                                if indicators_initialized {
                                    execute_trading_logic(
                                        client,
                                        config,
                                        state,
                                        market,
                                        &strategy,
                                        &price_window,
                                        current_bb_output,
                                        indicators_initialized
                                    ).await?;
                                }
                            }
                        }  

                        // Check for status update after processing ticker
                        if status_tracker.should_update() {
                            if let Some(market) = &current_market_price {
                                print_detailed_status(
                                    market,
                                    state,
                                    &strategy,
                                    &price_window,
                                    current_bb_output,
                                    config
                                );
                                status_tracker.update();
                            }
                        }
                    }

                    // Process klines
                    if let Ok(combined) = serde_json::from_str::<CombinedStream<KlineData>>(&msg_str) {
                        if handle_kline_update(
                            &combined.data,
                            &mut last_candle_time,
                            &mut price_window,
                            &strategy,
                            &mut current_bb_output,
                            &mut indicators_initialized,
                            config
                        )? {
                            // New candle processed, execute trading logic
                            if let Some(market) = &current_market_price {
                                execute_trading_logic(
                                    client,
                                    config,
                                    state,
                                    market,
                                    &strategy,
                                    &price_window,
                                    current_bb_output,
                                    indicators_initialized,
                                ).await?;
                            }
                        }
                    }
                }
                Some(Err(e)) => {
                    eprintln!("WebSocket error: {}", e);
                    state.error_count += 1;
                    if state.error_count > 3 {
                        return Err(e.into());
                    }
                    tokio::time::sleep(Duration::from_secs(config.reconnect_delay)).await;
                }
                None => {
                    println!("WebSocket stream ended");
                    break;
                }
            }
        }
    }

    Ok(())
}

// Helper function to handle depth updates
fn handle_depth_update(
    market: &mut MarketPrice,
    depth_data: &DepthStreamData,
    config: &TradingConfig,
) -> Result<(), Box<dyn Error>> {
    let bids: Vec<(f64, f64)> = depth_data.bids.iter()
        .filter_map(|arr| {
            match (arr[0].parse::<f64>(), arr[1].parse::<f64>()) {
                (Ok(price), Ok(qty)) if price > 0.0 && qty > 0.0 => Some((price, qty)),
                _ => None,
            }
        })
        .collect();

    let asks: Vec<(f64, f64)> = depth_data.asks.iter()
        .filter_map(|arr| {
            match (arr[0].parse::<f64>(), arr[1].parse::<f64>()) {
                (Ok(price), Ok(qty)) if price > 0.0 && qty > 0.0 => Some((price, qty)),
                _ => None,
            }
        })
        .collect();

    market.update_depth_from_arrays(&bids, &asks)?;
    Ok(())
}

// Helper function to handle book ticker updates
fn handle_book_ticker_update(
    current_market_price: &mut Option<MarketPrice>,
    ticker_data: &BookTickerData,
    price_window: &mut PriceWindow,  // Add price_window parameter
    strategy: &TradingStrategy,      // Add strategy parameter
    current_bb_output: &mut Option<(f64, f64, f64)>,  // Add BB output parameter
    config: &TradingConfig,
) -> Result<bool, Box<dyn Error>> {
    if let Ok(market_price) = MarketPrice::from_book_ticker(ticker_data) {
        if let Some(existing_market) = current_market_price {
            *existing_market = MarketPrice {
                bid: market_price.bid,
                ask: market_price.ask,
                bid_quantity: market_price.bid_quantity,
                ask_quantity: market_price.ask_quantity,
                mid: market_price.mid,
                depth_bids: existing_market.depth_bids.clone(),
                depth_asks: existing_market.depth_asks.clone(),
                total_bid_liquidity: existing_market.total_bid_liquidity,
                total_ask_liquidity: existing_market.total_ask_liquidity,
                last_update: SystemTime::now(),
            };

            // Update price window with latest mid price
            price_window.add(market_price.mid, config);

            // Update strategy indicators
            match strategy {
                TradingStrategy::Bollinger => {
                    if price_window.is_ready(config) {
                        *current_bb_output = price_window.calculate_bb();
                    }
                },
                TradingStrategy::MACrossover => {
                    // MAs will be updated automatically in price_window.add()
                }
            }
            return Ok(true);
        } else {
            *current_market_price = Some(market_price);
            return Ok(true);
        }
    }
    Ok(false)
}

// Helper function to handle kline updates
fn handle_kline_update(
    kline_data: &KlineData,
    last_candle_time: &mut i64,
    price_window: &mut PriceWindow,
    strategy: &TradingStrategy,
    current_bb_output: &mut Option<(f64, f64, f64)>,
    indicators_initialized: &mut bool,
    config: &TradingConfig,
) -> Result<bool, Box<dyn Error>> {
    let start_time = kline_data.kline.start.as_i64();
    if start_time <= *last_candle_time {
        return Ok(false);
    }

    if let Ok(close_price) = kline_data.kline.close.parse::<f64>() {
        *last_candle_time = start_time;
        price_window.add(close_price, config);

        match strategy {
            TradingStrategy::Bollinger => {
                if price_window.is_ready(config) {
                    *current_bb_output = price_window.calculate_bb();
                }
            },
            TradingStrategy::MACrossover => {
                if !*indicators_initialized && price_window.get_latest_mas().is_some() {
                    *indicators_initialized = true;
                }
            }
        }
        Ok(true)
    } else {
        Ok(false)
    }
}

// Helper function to execute trading logic

// Add this function to monitor prices and execute trades in real-time
async fn execute_trading_logic(
    client: &Client,
    config: &TradingConfig,
    state: &mut TradingState,
    market: &MarketPrice,
    strategy: &TradingStrategy,
    price_window: &PriceWindow,
    current_bb_output: Option<(f64, f64, f64)>,
    indicators_initialized: bool,
) -> Result<(), Box<dyn Error>> {
    if !indicators_initialized {
        return Ok(());
    }

    // 1. Real-time position monitoring and risk management
    state.position.update_unrealized_pnl(market.mid);
    let min_profitable_move = calculate_min_profitable_move(market.mid, config.quantity, 0.001);

    // Check stop loss in real-time (using ticker data)
    if should_stop_loss(market.bid, &state.position, config.stop_loss_pct) {
        if let Some(weighted_bid) = market.get_execution_price("SELL", state.position.size) {
            // 2. Use order book depth for better execution
            if weighted_bid > market.bid * 0.9995 { // Ensure reasonable execution price
                println!("Stop loss triggered - Executing at weighted price: ${:.2}", weighted_bid);
                state.execute_trade(client, config, "SELL", weighted_bid, state.position.size).await?;
            } else {
                // Emergency market exit if depth is insufficient
                println!("Emergency stop loss - Market exit at: ${:.2}", market.bid);
                state.execute_trade(client, config, "SELL", market.bid, state.position.size).await?;
            }
        }
        return Ok(());
    }

    // 3. Strategy-specific execution with order book awareness
    match strategy {
        TradingStrategy::Bollinger => {
            if let Some((bb_lower, bb_middle, bb_upper)) = current_bb_output {
                // Sell signals with depth consideration
                if market.bid > bb_upper && state.position.size > 0.0 {
                    if let Some(weighted_bid) = market.get_execution_price("SELL", state.position.size) {
                        let potential_profit = weighted_bid - state.position.average_price;
                        if weighted_bid > bb_upper && potential_profit > min_profitable_move {
                            println!("BB Exit Signal - Executing at weighted price: ${:.2}", weighted_bid);
                            state.execute_trade(client, config, "SELL", weighted_bid, state.position.size).await?;
                        }
                    }
                } 
                // Buy signals with depth consideration
                else if market.ask < bb_lower && 
                    state.can_add_position(config, market.ask) && 
                    state.position.can_trade() {
                    let buy_quantity = config.quantity.min(state.available_position(config, market.ask));
                    
                    // Check market depth for entry
                    if let Some(weighted_ask) = market.get_execution_price("BUY", buy_quantity) {
                        if weighted_ask < bb_lower && buy_quantity > 0.0 {
                            // Validate liquidity is sufficient
                            if market.total_ask_liquidity >= buy_quantity * 1.5 { // 50% buffer
                                println!("BB Entry Signal - Executing at weighted price: ${:.2}", weighted_ask);
                                state.execute_trade(client, config, "BUY", weighted_ask, buy_quantity).await?;
                            } else {
                                println!("Insufficient liquidity for entry");
                            }
                        }
                    }
                }

                // Print real-time metrics
                println!(
                    "${:.2} | BB: {:.2}/{:.2}/{:.2} | Depth: {:.8}/{:.8} | Pos: {:.8} | P&L: ${:.2}",
                    market.mid, bb_lower, bb_middle, bb_upper,
                    market.total_bid_liquidity, market.total_ask_liquidity,
                    state.position.size, state.cumulative_return
                );
            }
        },
        TradingStrategy::MACrossover => {
            if let Some((short_ma, long_ma)) = price_window.get_latest_mas() {
                // Real-time signal monitoring
                let ma_spread = short_ma - long_ma;
                
                // Sell signal with depth consideration
                if short_ma < long_ma && state.position.size > 0.0 {
                    if let Some(weighted_bid) = market.get_execution_price("SELL", state.position.size) {
                        let potential_profit = weighted_bid - state.position.average_price;
                        if potential_profit > min_profitable_move {
                            println!("MA Exit Signal - Executing at weighted price: ${:.2}", weighted_bid);
                            state.execute_trade(client, config, "SELL", weighted_bid, state.position.size).await?;
                        }
                    }
                }
                // Buy signal with depth consideration
                else if short_ma > long_ma && 
                    state.can_add_position(config, market.ask) && 
                    state.position.can_trade() {
                    let buy_quantity = config.quantity.min(state.available_position(config, market.ask));
                    
                    if let Some(weighted_ask) = market.get_execution_price("BUY", buy_quantity) {
                        // Validate sufficient liquidity
                        if market.total_ask_liquidity >= buy_quantity * 1.5 && 
                           ma_spread > min_profitable_move { // Additional trend strength check
                            println!("MA Entry Signal - Executing at weighted price: ${:.2}", weighted_ask);
                            state.execute_trade(client, config, "BUY", weighted_ask, buy_quantity).await?;
                        }
                    }
                }

                // Print real-time metrics including order book depth
                println!(
                    "${:.2} | MA Spread: ${:.2} | Depth: {:.8}/{:.8} | Pos: {:.8} | P&L: ${:.2}",
                    market.mid, ma_spread,
                    market.total_bid_liquidity, market.total_ask_liquidity,
                    state.position.size, state.cumulative_return
                );
            }
        }
    }

    // Regular market validation
    if let Err(e) = state.market_validator.validate_price(market) {
        println!("Market validation issue: {}", e);
    }
    
    // Update risk metrics
    state.update_risk_metrics(market.mid, state.position_value());


    Ok(())
}


// Implementation of strategy-specific trading logic
async fn execute_bollinger_strategy(
    client: &Client,
    config: &TradingConfig,
    state: &mut TradingState,
    market: &MarketPrice,
    bb_lower: f64,
    bb_middle: f64,
    bb_upper: f64,
    min_profitable_move: f64,
) -> Result<(), Box<dyn Error>> {
    // Sell signals
    if market.bid > bb_upper && state.position.size > 0.0 {
        if let Some(weighted_bid) = market.get_execution_price("SELL", state.position.size) {
            if weighted_bid > bb_upper {
                let potential_profit = weighted_bid - state.position.average_price;
                if potential_profit > min_profitable_move {
                    state.execute_trade(client, config, "SELL", weighted_bid, state.position.size).await?;
                }
            }
        }
    } 
    // Buy signals
    else if market.ask < bb_lower &&
        state.can_add_position(config, market.ask) && 
        state.position.can_trade() {
        let buy_quantity = config.quantity.min(state.available_position(config, market.ask));
        if let Some(weighted_ask) = market.get_execution_price("BUY", buy_quantity) {
            if weighted_ask < bb_lower && buy_quantity > 0.0 {
                state.execute_trade(client, config, "BUY", weighted_ask, buy_quantity).await?;
            }
        }
    }
    Ok(())
}

async fn execute_ma_strategy(
    client: &Client,
    config: &TradingConfig,
    state: &mut TradingState,
    market: &MarketPrice,
    short_ma: f64,
    long_ma: f64,
    min_profitable_move: f64,
) -> Result<(), Box<dyn Error>> {
    if short_ma > long_ma && 
        state.can_add_position(config, market.ask) && 
        state.position.can_trade() {
        let buy_quantity = config.quantity.min(state.available_position(config, market.ask));
        if let Some(weighted_ask) = market.get_execution_price("BUY", buy_quantity) {
            if buy_quantity > 0.0 {
                state.execute_trade(client, config, "BUY", weighted_ask, buy_quantity).await?;
            }
        }
    } else if short_ma < long_ma && state.position.size > 0.0 {
        if let Some(weighted_bid) = market.get_execution_price("SELL", state.position.size) {
            let potential_profit = weighted_bid - state.position.average_price;
            if potential_profit > min_profitable_move {
                state.execute_trade(client, config, "SELL", weighted_bid, state.position.size).await?;
            }
        }
    }
    Ok(())
}

async fn handle_stop_loss(
    client: &Client,
    config: &TradingConfig,
    state: &mut TradingState,
    market: &MarketPrice,
) -> Result<(), Box<dyn Error>> {
    if let Some(weighted_bid) = market.get_execution_price("SELL", state.position.size) {
        state.execute_trade(client, config, "SELL", weighted_bid, state.position.size).await?;
    } else {
        // Emergency market exit if depth is insufficient
        state.execute_trade(client, config, "SELL", market.bid, state.position.size).await?;
    }
    Ok(())
}

async fn handle_price_update(
    client: &Client,
    config: &TradingConfig,
    state: &mut TradingState,
    market: &MarketPrice,
    strategy: &TradingStrategy,
    price_window: &PriceWindow,
    current_bb_output: Option<(f64, f64, f64)>,
    indicators_initialized: bool,
    last_print: &mut SystemTime,
) -> Result<(), Box<dyn Error>> {
    // Only print if enough time has elapsed (e.g., every second)
    if last_print.elapsed()?.as_secs() >= 1 {
        match strategy {
            TradingStrategy::MACrossover => {
                if let Some((short_ma, long_ma)) = price_window.get_latest_mas() {
                    println!(
                        "${:.2} | MA({}): {:.2} | MA({}): {:.2} | Pos: {:.8} | P&L: ${:.2} | Unrealized P&L: ${:.2} | Min Exit Move: ${:.2}",
                        market.mid, 
                        config.ma_short_period, short_ma,
                        config.ma_long_period, long_ma,
                        state.position.size,
                        state.cumulative_return,
                        state.position.unrealized_pnl,
                        calculate_min_profitable_move(market.mid, config.quantity, 0.001)
                    );
                }
            },
            TradingStrategy::Bollinger => {
                if let Some((bb_lower, bb_middle, bb_upper)) = current_bb_output {
                    println!(
                        "${:.2} | BB: {:.2}/{:.2}/{:.2} | Pos: {:.8} | P&L: ${:.2} | Unrealized P&L: ${:.2} | Min Exit Move: ${:.2}",
                        market.mid, bb_lower, bb_middle, bb_upper,
                        state.position.size,
                        state.cumulative_return,
                        state.position.unrealized_pnl,
                        calculate_min_profitable_move(market.mid, config.quantity, 0.001)
                    );
                }
            }
        }
        *last_print = SystemTime::now();
    }
    Ok(())
}



// Print trading status periodically

struct StatusTracker {
    last_update: SystemTime,
    update_interval: Duration,
}

impl StatusTracker {
    fn new(interval_secs: u64) -> Self {
        Self {
            last_update: SystemTime::now(),
            update_interval: Duration::from_secs(interval_secs),
        }
    }

    fn should_update(&self) -> bool {
        self.last_update.elapsed().unwrap_or_default() >= self.update_interval
    }

    fn update(&mut self) {
        self.last_update = SystemTime::now();
    }
}

fn print_detailed_status(
    market: &MarketPrice,
    state: &TradingState,
    strategy: &TradingStrategy,
    price_window: &PriceWindow,
    current_bb_output: Option<(f64, f64, f64)>,
    config: &TradingConfig,
) {
    println!("\n========== TRADING STATUS UPDATE ==========");
    println!("Time: {}", chrono::Local::now().format("%Y-%m-%d %H:%M:%S"));
    
    // Market Information
    println!("\n📊 MARKET STATUS");
    println!("Current Price: ${:.2}", market.mid);
    println!("Spread: ${:.2}", market.ask - market.bid);
    println!("Book Depth - Bids: {:.8} BTC, Asks: {:.8} BTC", 
        market.total_bid_liquidity, 
        market.total_ask_liquidity
    );

    // Strategy Information
    println!("\n📈 STRATEGY STATUS");
    match strategy {
        TradingStrategy::Bollinger => {
            if let Some((lower, middle, upper)) = current_bb_output {
                println!("Strategy: Bollinger Bands");
                println!("Upper Band: ${:.2}", upper);
                println!("Middle Band: ${:.2}", middle);
                println!("Lower Band: ${:.2}", lower);
                println!("Band Width: ${:.2}", upper - lower);
            }
        },
        TradingStrategy::MACrossover => {
            if let Some((short_ma, long_ma)) = price_window.get_latest_mas() {
                println!("Strategy: MA Crossover");
                println!("Short MA({}): ${:.2}", config.ma_short_period, short_ma);
                println!("Long MA({}): ${:.2}", config.ma_long_period, long_ma);
                println!("MA Spread: ${:.2}", short_ma - long_ma);
            }
        }
    }

    // Position Information
    println!("\n💼 POSITION STATUS");
    println!("Current Position: {:.8} BTC", state.position.size);
    if state.position.size != 0.0 {
        println!("Entry Price: ${:.2}", state.position.average_price);
        println!("Position Value: ${:.2}", state.position.value_in_usdt);
    }

    // Performance Metrics
    println!("\n📊 PERFORMANCE METRICS");
    println!("Total P&L: ${:.2}", state.cumulative_return);
    println!("Unrealized P&L: ${:.2}", state.position.unrealized_pnl);
    println!("Total Trades: {}", state.total_trades);
    if state.total_trades > 0 {
        let win_rate = (state.profitable_trades as f64 / state.total_trades as f64) * 100.0;
        println!("Win Rate: {:.1}%", win_rate);
    }

    // Risk Metrics
    println!("\n⚠️ RISK METRICS");
    println!("Stop Loss Level: ${:.2}", 
        if state.position.size > 0.0 {
            state.position.average_price * (1.0 - config.stop_loss_pct)
        } else {
            0.0
        }
    );
    
    println!("==========================================\n");
}


fn print_trading_status(
    market: &MarketPrice,
    state: &TradingState,
    strategy: &TradingStrategy,
    price_window: &PriceWindow,
    current_bb_output: Option<(f64, f64, f64)>,
    config: &TradingConfig,
) {
    println!("\n=== Trading Status ===");
    println!("Price: ${:.2} (Spread: ${:.2})", 
        market.mid,
        market.ask - market.bid
    );
    println!("Position: {:.8} BTC @ ${:.2}", 
        state.position.size,
        state.position.average_price
    );
    println!("P&L: ${:.2} (Unrealized: ${:.2})", 
        state.cumulative_return,
        state.position.unrealized_pnl
    );

    // Strategy-specific indicators
    match strategy {
        TradingStrategy::MACrossover => {
            if let Some((short_ma, long_ma)) = price_window.get_latest_mas() {
                println!("MA({}/{}): ${:.2}/${:.2} Diff: ${:.2}", 
                    config.ma_short_period, 
                    config.ma_long_period,
                    short_ma, 
                    long_ma,
                    short_ma - long_ma
                );
            }
        },
        TradingStrategy::Bollinger => {
            if let Some((lower, middle, upper)) = current_bb_output {
                println!("Bollinger Bands: ${:.2} / ${:.2} / ${:.2}", 
                    lower, middle, upper
                );
            }
        }
    }

    // Trading statistics
    println!("Total Trades: {} (Profitable: {})", 
        state.total_trades,
        state.profitable_trades
    );
    if state.total_trades > 0 {
        println!("Win Rate: {:.1}%", 
            (state.profitable_trades as f64 / state.total_trades as f64) * 100.0
        );
    }
    println!("========================\n");
}

// Application entry point
#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    // Parse command line arguments
    let args: Vec<String> = env::args().collect();
    
    // Load trading configuration from file/environment
    let config = load_trading_config()?;
    
    // Initialize trading state and HTTP client
    let mut state = TradingState::new();
    let client = reqwest::Client::new();

    // Handle different command modes
    match args.get(1).map(|s| s.as_str()) {
        // If "report" command is given, just generate report
        Some("report") => {
            state.export_session_report()?;
        },
        // If no command is given, start trading
        None => {
            println!("Starting trading bot for {}. Press Ctrl+C to stop and generate report.", config.symbol);
            
            // Create a separate task for the trading loop
            // This allows us to handle the shutdown signal independently
            let trading_handle = tokio::spawn(async move {
                if let Err(e) = run_trading_loop(&client, &config, &mut state).await {
                    eprintln!("Error in trading loop: {}", e);
                    state.error_count += 1;
                }
                // Return the state so we can generate final report
                state
            });

            // Wait for Ctrl+C
            if let Err(e) = ctrl_c().await {
                eprintln!("Error setting up Ctrl+C handler: {}", e);
            } else {
                println!("\nShutdown signal received, closing positions and generating report...");
            }

            // Get the state back from the trading task
            if let Ok(final_state) = trading_handle.await {
                // Generate final report
                final_state.export_session_report()?;
                println!("Session report generated successfully. Shutting down.");
            }
        },
        Some(cmd) => {
            return Err(format!("Unknown command: {}", cmd).into());
        }
    }

    Ok(())
}