@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable int id) {
        return null;
    }

    @DeleteMapping("/{id}")
    public void cancelOrder(@PathVariable int id) {
    }
}