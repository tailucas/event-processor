package tailucas.app.api;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import tailucas.app.AppProperties;

@RestController
public class EventHandler {

	@Autowired
    private Logger log;

	@Autowired
	private Environment env;

	@Autowired
	private AppProperties props;

	@GetMapping("/")
	public String index() {
		final String message = Thread.currentThread() + " spring.threads.virtual.enabled " + env.getProperty("spring.threads.virtual.enabled") + " " + props.getProjectName() + " " + props.getMessageControlExchangeName();
		log.atDebug().setMessage("Index")
			.addKeyValue("thread", Thread.currentThread().toString())
			.addKeyValue("virtual_threads_enabled", env.getProperty("spring.threads.virtual.enabled"))
			.addKeyValue("project_name", props.getProjectName())
			.addKeyValue("control_exchange_name", props.getMessageControlExchangeName())
			.log();
		return message;
	}
}
