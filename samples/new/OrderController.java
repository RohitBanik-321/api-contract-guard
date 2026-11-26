@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable int id) {
        return null;
    }

    // BREAKING: @DeleteMapping("/{id}") was removed!
    // SAFE: New endpoint added below
    @PostMapping("/track")
    public String trackOrder() {
        return "ON_THE_WAY";
    }
}