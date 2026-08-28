output "server_ipv4" {
  description = "Point the domain's DNS A record here (I0/I4), and set HETZNER_HOST to this in both repos' GitHub secrets."
  value       = hcloud_server.app.ipv4_address
}

output "server_ipv6" {
  value = hcloud_server.app.ipv6_address
}
