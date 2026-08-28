variable "admin_username" {
  description = "Human admin account created on the server (sudo, key-only login)."
  type        = string
  default     = "bjarne"
}

variable "admin_ssh_public_key" {
  description = "Public key for the admin account (and Hetzner's root-bootstrap mechanism)."
  type        = string
}

variable "deploy_ssh_public_key" {
  description = <<-EOT
    Public half of the CI deploy key. The private half is the HETZNER_SSH_KEY secret in both the
    backend and frontend GitHub repos (they share one key/user since both deploy to this server).
    Restricted server-side to /opt/video-service/deploy.sh - never gets root or a shell.
  EOT
  type        = string
}

variable "server_type" {
  description = "Hetzner server type. CLAUDE.md §11 Phase 0 = cx33; bump to cx43 then cx53 as usage grows - just change this and re-apply, no other change needed."
  type        = string
  default     = "cx33"
}

variable "location" {
  description = "Hetzner location. CLAUDE.md §11: Falkenstein or Nuremberg - keep this the same as wherever the object storage bucket ends up, to keep traffic between them internal."
  type        = string
  default     = "nbg1"
}
