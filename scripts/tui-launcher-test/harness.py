import os, pty, sys, time, subprocess, signal, select, fcntl, termios, struct
REL="/rel"
def sh(cmd):
    r=subprocess.run(cmd,shell=True,capture_output=True,text=True); return (r.stdout+r.stderr).strip()
def tm(args): return sh(f"tmux -u -L bitchat-tui {args}")
def start(term="linux", stdin_tty=True, rows=67, cols=240, argv=None, extra_env=None):
    for f in ("/tmp/keys.log","/tmp/app.env","/tmp/journal.log","/tmp/launcher.out"):
        try: os.remove(f)
        except FileNotFoundError: pass
    m,s=pty.openpty()
    fcntl.ioctl(s, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
    out=open("/tmp/launcher.out","wb")
    env=dict(os.environ, TERM=term, LANG="C.UTF-8", BITCHAT_TUI_FORCE_CONSOLE_SAFE="1"); env.update(extra_env or {})
    def pre():
        os.setsid()
        if stdin_tty: fcntl.ioctl(0, termios.TIOCSCTTY, 0)
    # Like the unit: only stdin is the terminal; stdout and stderr go elsewhere (the journal).
    p=subprocess.Popen(argv or [f"{REL}/bitchat-tui-launcher"], stdin=(s if stdin_tty else subprocess.DEVNULL), stdout=out, stderr=out, env=env, preexec_fn=pre, close_fds=True)
    os.close(s)
    return p,m
def drain(m, secs=0.5):
    buf=b""; end=time.time()+secs
    while time.time()<end:
        r,_,_=select.select([m],[],[],0.1)
        if r:
            try: buf+=os.read(m,65536)
            except OSError: break
    return buf
def wait_for(pred, secs=8):
    end=time.time()+secs
    while time.time()<end:
        if pred(): return True
        time.sleep(0.1)
    return False
def keys(): 
    try: return open("/tmp/keys.log").read().strip()
    except FileNotFoundError: return "<none>"
results=[]
def check(name, ok, detail=""):
    results.append(ok); print(("PASS " if ok else "FAIL ")+name+(": "+detail if detail else "")); sys.stdout.flush()

print("tmux version:", sh("tmux -V"))
# ---- T1: session comes up with only stdin a terminal; options; no bindings; env reaches the app
p,m=start()
check("T1 app started in a pane", wait_for(lambda: os.path.exists("/tmp/keys.log")), open("/tmp/app.env").read().replace("\n"," ") if os.path.exists("/tmp/app.env") else "no app.env")
screen=drain(m,1.0)
check("T1 console client draws the app's screen on the terminal that is only stdin", b"FAKE-APP-SCREEN" in screen, repr(screen[-60:]))
check("T1 one client on the console pty", tm("list-clients -F '#{client_tty} #{client_termname}'").count("\n")==0 and "linux" in tm("list-clients -F '#{client_tty} #{client_termname}'"), tm("list-clients -F '#{client_tty} #{client_termname}'"))
opts=tm("show-options -g status")+" | "+tm("show-options -g prefix")+" | "+tm("show-options -s escape-time")+" | "+tm("show-options -g window-size")
check("T1 options", "status off" in opts and "prefix None" in opts and "escape-time 25" in opts and "window-size latest" in opts, opts)
lk=tm("list-keys")
check("T1 no key bindings at all", lk=="" , lk[:200])
env=open("/tmp/app.env").read()
check("T1 app sees FORCE=1 and a pts, not TERM=linux", "FORCE=1" in env and "/dev/pts/" in env and "TERM=screen" in env, env.replace("\n"," "))
check("T1 pane has the console's size (no status row)", "size=67 240" in env, env.replace("\n"," "))
check("T1 app stdout reached the journal stand-in, identity second", open("/tmp/journal.log").read().splitlines()[:2]==["=== bitchat TUI ===","fake identity line"], repr(open("/tmp/journal.log").read()[:80]))
# ---- T2: keys. Esc alone, console Shift+Tab (ESC TAB), tmux's default prefix Ctrl+B then d, arrow up, Ctrl+] 
def send(b, pause=0.4):
    os.write(m,b); time.sleep(pause)
send(b"\x1b"); k1=keys()
check("T2 bare Esc arrives as one 1b", k1=="1b", k1)
send(b"\x1b\t"); k2=keys()[len(k1):].strip()
check("T2 console Shift+Tab (ESC TAB) arrives as ESC TAB or CSI Z", k2 in ("1b 09","1b 5b 5a"), k2)
before=keys(); send(b"\x02d"); k3=keys()[len(before):].strip()
check("T2 Ctrl+B d reaches the app and does not detach", k3=="02 64" and "linux" in tm("list-clients -F '#{client_termname}'"), k3)
before=keys(); send(b"\x1b[A"); k4=keys()[len(before):].strip()
check("T2 arrow up arrives as an arrow sequence", k4 in ("1b 5b 41","1b 4f 41"), k4)
before=keys(); send(b"\x1d:q"); k5=keys()[len(before):].strip()
check("T2 Ctrl+] : q all reach the app", k5=="1d 3a 71", k5)
# ---- T3: SSH-like second client with an unknown TERM through the attach script; then hang it up
m2,s2=pty.openpty(); fcntl.ioctl(s2, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))
def pre2():
    os.setsid(); fcntl.ioctl(0, termios.TIOCSCTTY, 0)
a=subprocess.Popen([f"{REL}/bitchat-tui-attach"], stdin=s2, stdout=s2, stderr=s2, env=dict(os.environ, TERM="xterm-ghostty", LANG="C"), preexec_fn=pre2, close_fds=True); os.close(s2)
ok=wait_for(lambda: tm("list-clients -F '#{client_termname}'").count("\n")==1)
check("T3 attach script attaches from TERM=xterm-ghostty (falls back to xterm-256color)", ok, tm("list-clients -F '#{client_tty} #{client_termname} #{client_width}x#{client_height}'").replace("\n"," ; "))
scr2=drain(m2,1.0)
check("T3 second client sees the same screen", b"FAKE-APP-SCREEN" in scr2, repr(scr2[-50:]))
time.sleep(0.5); env=open("/tmp/app.env").read()
check("T3 app got a resize to the newer client's size", "size=40 120" in env, env.replace("\n"," "))
before=keys(); os.write(m2,b"z"); time.sleep(0.4)
check("T3 a key typed in the second client reaches the same app", keys()[len(before):].strip()=="7a", keys()[len(before):].strip())
os.close(m2); a.wait(timeout=5)
ok=wait_for(lambda: tm("list-clients -F '#{client_termname}'")=="linux")
check("T3 after the SSH client hangs up: app alive, console still attached", ok and p.poll() is None and tm("has-session -t bitchat-tui")=="", tm("list-clients -F '#{client_termname}'"))
# ---- T4: kill the console client; the launcher must attach again
cpid=tm("list-clients -F '#{client_pid}'")
os.kill(int(cpid), signal.SIGKILL)
ok=wait_for(lambda: (lambda c: c.isdigit() and c!=cpid)(tm("list-clients -F '#{client_pid}'")))
check("T4 console client killed -> launcher attaches a new one, app untouched", ok and p.poll() is None, f"old {cpid} new {tm('list-clients -F \"#{client_pid}\"')}")
# ---- T5: exit status 75 from the app reaches the launcher
drain(m,0.3); os.write(m,b"x")
try: rc=p.wait(timeout=8)
except subprocess.TimeoutExpired: rc="timeout"
check("T5 app exit 75 -> launcher exit 75, server gone", rc==75 and "no server" in tm("has-session -t bitchat-tui")+tm("list-sessions") or (rc==75 and tm("has-session -t bitchat-tui")!=""), f"rc={rc} tmux='{tm('has-session -t bitchat-tui')}'")
check("T5 no state dir left", sh("ls -d /tmp/bitchat-tui.* 2>/dev/null")=="", sh("ls -d /tmp/bitchat-tui.* 2>/dev/null"))
os.close(m)
# ---- T6: Ctrl+C -> app exits 0 -> launcher exits 0
p,m=start(); wait_for(lambda: os.path.exists("/tmp/keys.log")); drain(m,0.5); os.write(m,b"\x03")
try: rc=p.wait(timeout=8)
except subprocess.TimeoutExpired: rc="timeout"
check("T6 Ctrl+C -> launcher exit 0", rc==0, f"rc={rc}"); os.close(m)
# ---- T7: tmux server dies -> launcher exits non-zero (systemd restarts the unit)
p,m=start(); wait_for(lambda: os.path.exists("/tmp/keys.log")); drain(m,0.5); tm("kill-server")
try: rc=p.wait(timeout=8)
except subprocess.TimeoutExpired: rc="timeout"
check("T7 server killed -> launcher exits non-zero", rc not in (0,75,"timeout"), f"rc={rc}"); time.sleep(1.0)
left=sh("pgrep -af bitchat-tui.kexe | grep -v pgrep || true")
check("T7 (no systemd here) the hangup alone also ends the app", left=="", left); sh("pkill -9 -f bitchat-tui.kexe || true"); os.close(m)
# ---- T8: console cannot attach (stdin is not a terminal) -> bounded fallback to direct
p,m=start(stdin_tty=False)
try: rc=p.wait(timeout=20)
except subprocess.TimeoutExpired: rc="timeout"; p.kill()
sh("pkill -9 -f bitchat-tui.kexe || true")
out=open("/tmp/launcher.out").read()
check("T8 attach impossible -> session ended, app run directly, reason logged", "could not attach" in out and "=== bitchat TUI ===" in out and tm("has-session -t bitchat-tui")!="", f"rc={rc} out={out[:160]!r}"); os.close(m)
# ---- T9: stale server from before -> replaced, not a reason to go direct
tm(f"-f {REL}/bitchat-tui.tmux.conf new-session -d -s bitchat-tui 'sleep 600'")
p,m=start(); ok=wait_for(lambda: os.path.exists("/tmp/keys.log"))
check("T9 stale session replaced by a fresh one running the app", ok and "direct" not in open("/tmp/launcher.out").read(), open("/tmp/launcher.out").read()[:120])
os.write(m,b"\x03"); p.wait(timeout=8); os.close(m)
# ---- T10: attach script with no session
r=subprocess.run([f"{REL}/bitchat-tui-attach"],capture_output=True,text=True,env=dict(os.environ,TERM="xterm-256color"))
check("T10 attach with no session says so and starts nothing", r.returncode==1 and "no shared session" in r.stdout and sh("pgrep -af bitchat-tui.kexe | grep -v pgrep || true")=="", (r.stdout+r.stderr).strip())
print("all keys the app saw in the last run:", keys()); print(f"RESULT: {sum(results)}/{len(results)} passed"); sys.exit(0 if all(results) else 1)
