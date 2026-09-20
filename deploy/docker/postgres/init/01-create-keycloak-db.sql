-- Runs once, on first initialization of an empty postgres data volume.
-- Keycloak gets its own database so its schema stays separate from the app's.
CREATE DATABASE keycloak;
