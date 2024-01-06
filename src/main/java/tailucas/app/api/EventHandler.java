package tailucas.app.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EventHandler {

	@Autowired
	private Environment env;

	@GetMapping("/")
	public String index() {
		return Thread.currentThread() + " spring.threads.virtual.enabled " + env.getProperty("spring.threads.virtual.enabled");
	}

}