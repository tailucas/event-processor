const int SAMPLE_INTERVAL_MS = 250;
const int LED_PIN = 13;
const int LED_DELAY = 50;

const int ACHANS = 6;
const int SAMPLE_MAX = 1023;
const float SAMPLE_HIGH = 90.0;

const char FIELD_NUM_SPLIT = ':';
const char FIELD_SEP = ',';

void blink_led() {
  digitalWrite(LED_PIN, HIGH);
  delay(LED_DELAY);
  digitalWrite(LED_PIN, LOW);
}

bool led_on = false;
void toggle_led() {
  if (!led_on) {
    digitalWrite(LED_PIN, HIGH);
    led_on = true;
  } else {
    digitalWrite(LED_PIN, LOW);
    led_on = false;
  }
}

void setup() {
  pinMode(LED_PIN, OUTPUT);
  // enable pull-ups
  pinMode(A0, INPUT_PULLUP);
  pinMode(A1, INPUT_PULLUP);
  pinMode(A2, INPUT_PULLUP);
  pinMode(A3, INPUT_PULLUP);
  pinMode(A4, INPUT_PULLUP);
  pinMode(A5, INPUT_PULLUP);
  // warm-up
  for (int i=0; i<20; i++) {
    toggle_led();
    delay(LED_DELAY);
  }
  Serial.begin(9600);
}

int sampled[ACHANS];
int normalized = 0;
void loop() {
  for (int c=0; c<ACHANS; c++) {
    sampled[c] = analogRead(c);
    // Pause here for 10 x 100us between pins
    // http://arduino.cc/en/Tutorial/AnalogInputPins
    delay(1);
  }
  for (int c=0; c<ACHANS; c++) {
    // write the field type
    Serial.print('A');
    // write the channel number
    Serial.print(c, DEC);
    Serial.print(FIELD_NUM_SPLIT);
    // write the normalized value with rounding trick
    normalized = (sampled[c]/1023.0)*100 + 0.0;
    Serial.print(normalized);
    if (c<ACHANS-1) {
      Serial.print(FIELD_SEP);
    }
  }
  // finish the row and flush
  Serial.println();
  Serial.flush();
  // show activity (beyond the tx LED)
  toggle_led();
  // rest here for a moment
  delay(SAMPLE_INTERVAL_MS);
}

