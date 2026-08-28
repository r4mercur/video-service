terraform {
  required_version = ">= 1.7"
  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.48"
    }
  }
}

# Reads HCLOUD_TOKEN from the environment (`export HCLOUD_TOKEN=...` before apply) - never put
# the token in this file or commit it into the state.
provider "hcloud" {}

# Only the admin key ever reaches Hetzner's own root-bootstrap mechanism (the `ssh_keys`
# attribute below writes into root's authorized_keys). The deploy key is intentionally NOT
# listed here - it only exists inside cloud-init, scoped to the unprivileged `deploy` user with
# a forced command (see cloud-init.yaml.tpl). It never has root or shell access, at any point.
resource "hcloud_ssh_key" "admin" {
  name       = "video-service-admin"
  public_key = var.admin_ssh_public_key
}

resource "hcloud_firewall" "web" {
  name = "video-service-web"

  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "22"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "80"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "443"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
}

resource "hcloud_server" "app" {
  name         = "video-service-app"
  server_type  = var.server_type
  location     = var.location
  image        = "ubuntu-24.04"
  ssh_keys     = [hcloud_ssh_key.admin.id]
  firewall_ids = [hcloud_firewall.web.id]

  user_data = templatefile("${path.module}/cloud-init.yaml.tpl", {
    admin_username        = var.admin_username
    admin_ssh_public_key  = var.admin_ssh_public_key
    deploy_ssh_public_key = var.deploy_ssh_public_key
    deploy_script         = file("${path.module}/deploy.sh")
    backup_script         = file("${path.module}/backup.sh")
  })
}
