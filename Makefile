APP := event_processor
DOCKER_APP := event-processor

all: help

help:
	@echo "Depends on 1Password Connect Server: https://developer.1password.com/docs/connect/get-started"

setup: docker-compose.yml
	@echo "Generating docker-compose.yml"
	python3 pylib/cred_tool ENV $(APP) | python3 pylib/yaml_interpol services/app/environment docker-compose.template > docker-compose.yml

run:
	docker-compose up

connect:
	./connect_to_app.sh $(DOCKER_APP)

clean:
	rm docker-compose.yml

.PHONY: all help setup run connect clean