CREATE TABLE general_config (
	id INTEGER NOT NULL, 
	config_key VARCHAR(50) NOT NULL, 
	config_value TEXT NOT NULL, 
	PRIMARY KEY (id)
);
CREATE TABLE event_log (
	id INTEGER NOT NULL, 
	input_device VARCHAR(100), 
	output_device VARCHAR(100), 
	timestamp DATETIME, 
	PRIMARY KEY (id)
);
CREATE INDEX ix_event_log_output_device ON event_log (output_device);
CREATE INDEX ix_event_log_input_device ON event_log (input_device);
CREATE INDEX ix_event_log_timestamp ON event_log (timestamp);
CREATE TABLE input_config (
	id INTEGER NOT NULL, 
	device_key VARCHAR(50) NOT NULL, 
	device_type VARCHAR(100) NOT NULL, 
	device_label VARCHAR(100), 
	customized BOOLEAN, 
	activation_interval INTEGER, 
	auto_schedule BOOLEAN, 
	auto_schedule_enable VARCHAR(5), 
	auto_schedule_disable VARCHAR(5), 
	device_enabled BOOLEAN, 
	multi_trigger BOOLEAN, 
	group_name VARCHAR(100), 
	info_notify BOOLEAN, 
	PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ix_input_config_device_key ON input_config (device_key);
CREATE INDEX ix_input_config_group_name ON input_config (group_name);
CREATE TABLE output_config (
	id INTEGER NOT NULL, 
	device_key VARCHAR(50) NOT NULL, 
	device_type VARCHAR(100) NOT NULL, 
	device_label VARCHAR(100), 
	device_params TEXT, 
	PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ix_output_config_device_key ON output_config (device_key);
CREATE TABLE input_link (
	id INTEGER NOT NULL, 
	input_device_id INTEGER NOT NULL, 
	linked_device_id INTEGER NOT NULL, 
	PRIMARY KEY (id), 
	CONSTRAINT unique_link UNIQUE (input_device_id, linked_device_id), 
	FOREIGN KEY(input_device_id) REFERENCES input_config (id)
);
CREATE INDEX ix_input_link_input_device_id ON input_link (input_device_id);
CREATE TABLE output_link (
	id INTEGER NOT NULL, 
	input_device_id INTEGER NOT NULL, 
	output_device_id INTEGER NOT NULL, 
	PRIMARY KEY (id), 
	CONSTRAINT unique_link UNIQUE (input_device_id, output_device_id), 
	FOREIGN KEY(input_device_id) REFERENCES input_config (id), 
	FOREIGN KEY(output_device_id) REFERENCES output_config (id)
);
CREATE INDEX ix_output_link_input_device_id ON output_link (input_device_id);
CREATE TABLE IF NOT EXISTS "meter_config" (
	"id"	INTEGER NOT NULL,
	"input_device_id"	INTEGER NOT NULL,
	"meter_value"	INTEGER NOT NULL,
	"register_value"	INTEGER NOT NULL,
	"meter_reading"	VARCHAR NOT NULL,
	"meter_iot_topic"	VARCHAR(100) NOT NULL,
	"meter_low_limit"	INTEGER,
	"meter_high_limit"	INTEGER,
	"meter_reset_value"	INTEGER,
	"meter_reset_additive"	BOOLEAN,
	"meter_reading_unit"	VARCHAR(10),
	"meter_reading_unit_factor"	INTEGER,
	"meter_reading_unit_precision"	INTEGER,
	PRIMARY KEY("id"),
	FOREIGN KEY("input_device_id") REFERENCES "input_config"("id")
);
CREATE INDEX "ix_meter_config_input_device_id" ON "meter_config" (
	"input_device_id"
);
