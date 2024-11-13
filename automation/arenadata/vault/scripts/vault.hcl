listener "tcp" {
  address       = "0.0.0.0:8200"
  tls_disable = 0
  tls_cert_file = "/certs/certificate.pem"
  tls_key_file  = "/certs/key.pem"
}

storage "inmem" {
}

default_lease_ttl = "168h"
max_lease_ttl = "720h"
ui = true

log_level = "trace"
