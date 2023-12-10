package tailucas.app;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EventHandler {

	@GetMapping("/")
	public String index() {
		return "meh";
	}

}
