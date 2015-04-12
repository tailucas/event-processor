const int SAMPLE_DELAY = 250;
const int LED_PIN = 13;

const int ACHANS = 6;
const int SAMPLE_MAX = 1023;
const float SAMPLE_HIGH = 90.0;

const char FIELD_NUM_SPLIT = ':';
const char FIELD_SEP = ',';

void setup() {
  pinMode(LED_PIN, OUTPUT);
  Serial.begin(9600);
}

bool high_value = false;
int sampled[ACHANS];
int normalized = 0;
void loop() {
  for (int c=0; c<ACHANS; c++) {
    sampled[c] = analogRead(c);
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
    high_value = high_value || (normalized >= SAMPLE_HIGH);
    if (c<ACHANS-1) {
      Serial.print(FIELD_SEP);
    }
  }
  // finish the row and flush
  Serial.println();
  Serial.flush();
  // indicate a high value seen
  if (high_value) {
    digitalWrite(LED_PIN, HIGH);
  } else {
    digitalWrite(LED_PIN, LOW);
  }
  high_value = false;
  // rest here for a moment
  delay(SAMPLE_DELAY);
}

