"""Persistent local TLS identity. Trust is established by comparing the SPKI hash."""
import hashlib
import os
import ssl
from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

from ..storage.trusted_devices import trusted_devices_path


def server_identity(path: Path | None = None) -> tuple[ssl.SSLContext, str]:
    path = path or trusted_devices_path().parent / "tls-identity.pem"
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if not path.exists():
        key = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Android Linux Bridge")])
        now = datetime.now(timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                .public_key(key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(now - timedelta(days=1)).not_valid_after(now + timedelta(days=3650))
                .sign(key, hashes.SHA256()))
        data = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                 serialization.NoEncryption()) + cert.public_bytes(serialization.Encoding.PEM)
        # Exclusive creation prevents two processes from replacing an existing identity.
        with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as file:
            file.write(data)
            file.flush()
            os.fsync(file.fileno())
    path.chmod(0o600)
    cert = x509.load_pem_x509_certificate(path.read_bytes())
    fingerprint = hashlib.sha256(cert.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest().upper()
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.set_ciphers("ECDHE+AESGCM:ECDHE+CHACHA20")
    context.load_cert_chain(path)
    return context, fingerprint
