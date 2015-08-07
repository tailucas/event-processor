
class APFun(Thread):

    def __init__(self):
        super(APFun, self).__init__()
        self.carriers = []
        self.surnames = []
        for surname in open('surnames.txt'):
            self.surnames.append(surname.strip())
        for carrier in open('flights.txt'):
            self.carriers.append(carrier.strip())
        self.destinations = ['counter', 'gate']
        self.last_message = None

    def run(self):
        while True:
            surname = random.choice(self.surnames)
            flight = random.choice(self.carriers)
            flight_number = '{} {} {}'.format(random.randint(1,9), random.randint(1,9), random.randint(1,9))
            destination = random.choice(self.destinations)
            destination_number = random.randint(1, 25)

            notification_queue.put("AP_GONG")
            message1 = 'Will passenger {} proceed immediately to {} {}.'.format(surname, destination, destination_number)
            message2 = 'Last call for passenger {} delaying the departure of flight {} {}.'.format(surname, flight, flight_number)
            message3 = 'For your safety, please do not leave any unattended baggage in the airport buildings.'
            message4 = 'An important announcement from Airports Company South Africa. Report suspicious baggage to security immediately.'
            output = random.choice([message1, message2, message3, message4])
            self.last_message = output
            notification_queue.put(output)
            sleep(15*random.random()+15)