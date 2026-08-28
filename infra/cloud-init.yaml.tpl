#cloud-config

package_update: true
package_upgrade: true

packages:
  - unattended-upgrades
  - apt-listchanges
  - fail2ban
  - ufw
  - restic
  - rsync
  - ca-certificates
  - curl
  - gnupg

# Providing this list at all (instead of extending it) suppresses cloud-init's distro default
# user - there is no "ubuntu" account on this box, only the two defined here.
users:
  - name: ${admin_username}
    groups: [sudo]
    shell: /bin/bash
    sudo: 'ALL=(ALL) NOPASSWD:ALL'
    ssh_authorized_keys:
      - ${admin_ssh_public_key}
  - name: deploy
    shell: /bin/bash
    lock_passwd: true
    ssh_authorized_keys:
      - 'command="/opt/video-service/deploy.sh",no-agent-forwarding,no-port-forwarding,no-pty,no-user-rc,no-X11-forwarding ${deploy_ssh_public_key}'

write_files:
  - path: /opt/video-service/deploy.sh
    owner: root:root
    permissions: '0755'
    content: |
      ${indent(6, deploy_script)}
  - path: /opt/video-service/backup.sh
    owner: root:root
    permissions: '0700'
    content: |
      ${indent(6, backup_script)}
  - path: /etc/fail2ban/jail.local
    content: |
      [sshd]
      enabled = true
  - path: /etc/apt/apt.conf.d/20auto-upgrades
    content: |
      APT::Periodic::Update-Package-Lists "1";
      APT::Periodic::Unattended-Upgrade "1";
  - path: /etc/cron.d/video-service-backup
    content: |
      15 3 * * * root /opt/video-service/backup.sh >> /var/log/video-service-backup.log 2>&1

runcmd:
  # Docker Engine + Compose plugin, official apt repo (docs.docker.com/engine/install/ubuntu).
  - install -m 0755 -d /etc/apt/keyrings
  - curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  - chmod a+r /etc/apt/keyrings/docker.asc
  - >
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc]
    https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable"
    > /etc/apt/sources.list.d/docker.list
  - apt-get update
  - apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
  - usermod -aG docker deploy

  # rrsync confines the deploy key's rsync transfers to /opt/video-service/incoming - ships
  # gzipped or plain depending on distro/rsync version, so try both known locations.
  - |
    set -e
    RRSYNC_GZ=$(find /usr/share -iname 'rrsync.gz' 2>/dev/null | head -n1)
    if [ -n "$RRSYNC_GZ" ]; then
      gunzip -c "$RRSYNC_GZ" > /usr/local/bin/rrsync
    else
      RRSYNC_PLAIN=$(find /usr/share -iname 'rrsync' -type f 2>/dev/null | head -n1)
      cp "$RRSYNC_PLAIN" /usr/local/bin/rrsync
    fi
    chmod 755 /usr/local/bin/rrsync

  # App directories - compose.prod.yaml/Caddyfile.prod land here via the first "deploy files"
  # run, .env and secrets/ are placed by hand (see infra/README.md), not by cloud-init.
  - mkdir -p /opt/video-service/secrets /opt/video-service/frontend-dist /opt/video-service/incoming/frontend
  # deploy.sh runs as the unprivileged `deploy` user (no sudo) and needs to create/overwrite
  # compose.prod.yaml, Caddyfile.prod and .env directly in this directory - it must own it, not
  # just the subdirectories. The sticky bit (mode 1755) still stops deploy from deleting/renaming
  # entries it doesn't own (secrets/, created next with different ownership), same protection
  # /tmp gets - so owning the parent doesn't hand deploy control over root-owned secrets/.
  - chown deploy:deploy /opt/video-service
  - chmod 1755 /opt/video-service
  - chown -R deploy:deploy /opt/video-service/incoming /opt/video-service/frontend-dist
  - chmod 700 /opt/video-service/secrets

  # SSH hardening - deliberately last, so a failure in an earlier step still leaves root
  # reachable (via the admin key, which Hetzner already put into root's authorized_keys) to fix
  # it. Once this runs, root login is closed entirely; use the admin user from here on.
  - sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
  - sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
  - systemctl restart ssh

  # ufw - defense in depth alongside the Hetzner Cloud Firewall (main.tf hcloud_firewall.web).
  - ufw allow 22/tcp
  - ufw allow 80/tcp
  - ufw allow 443/tcp
  - ufw --force enable
