import socket, threading, time, sys
# Broadcast-Adresse des eigenen Netzes eintragen (ifconfig / ip addr)
MY="cafe1234"; PORT=45678; BC="192.168.1.255"
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
s.setsockopt(socket.SOL_SOCKET,socket.SO_BROADCAST,1)
s.bind(("",PORT))
def tx(cmd): s.sendto(f"{MY}|{cmd}".encode(),(BC,PORT))
def presence():
    while True: tx("PING"); time.sleep(2)
threading.Thread(target=presence,daemon=True).start()
def script():
    time.sleep(5); print(">>> sende PLAY:dog",flush=True); tx("PLAY:dog")
threading.Thread(target=script,daemon=True).start()
s.settimeout(24); t0=time.time()
while time.time()-t0 < 24:
    try:
        d,a=s.recvfrom(128); msg=d.decode()
        if msg.startswith(MY): continue
        print(f"  [{time.time()-t0:5.1f}s] {a[0]} -> {msg}",flush=True)
    except socket.timeout: break
