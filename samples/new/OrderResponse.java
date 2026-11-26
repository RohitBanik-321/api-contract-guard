public class OrderResponse {
    private String orderId;          // BREAKING: Type changed int -> String
    private String eta;              // SAFE: New field added (deliveryTimeMinutes deleted!)
    private String restaurantName;   // SAFE: Unchanged
}