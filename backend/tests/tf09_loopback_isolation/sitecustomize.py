"""Sterile loopback-only process guard; use owned ephemeral endpoints, never a real service."""
import socket
import sys

TF09_LOOPBACK_ISOLATION = True
ports = set()
loopback = "127.0.0.1"

def network(event, args):
    if event == "socket.getaddrinfo" and not (args[0] == loopback and args[1] in ports):
        raise RuntimeError("egress denied")
    if event in {"socket.gethostbyaddr", "socket.gethostbyname", "socket.getnameinfo"}:
        raise RuntimeError("DNS denied")
    if event == "socket.bind" and args[0].family in {socket.AF_INET, socket.AF_INET6}:
        if args[1] != (loopback, 0):
            raise RuntimeError("only loopback ephemeral binds are allowed")
    if event in {"socket.connect", "socket.sendto"} and args[0].family in {socket.AF_INET, socket.AF_INET6}:
        if len(args[1]) != 2 or args[1][0] != loopback or args[1][1] not in ports:
            raise RuntimeError("non-fixture destination denied")

sys.addaudithook(network)
