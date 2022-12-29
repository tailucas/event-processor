# event_processor

Main processing coordinator for Home automation/security projects.

A basic dashboard is exposed using a [Flask](https://flask.palletsprojects.com/en/1.1.x/) server, and exposed externally through an [ngrok](https://ngrok.com/) tunnel. Basic [Telegram bot](https://core.telegram.org/bots) integration is used for external control. Exception handling is done using [Sentry](https://sentry.io/).

## Dependencies

* Same dependencies as [base-app](https://github.com/tailucas/base-app), including using the associated, built Docker image.
* Environment variables (TODO)