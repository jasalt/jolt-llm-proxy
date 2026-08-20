# Jolt login input blocked by a canonical tty with `icrnl` disabled

## Summary

The login prompt can appear to ignore `1` followed by Enter and display this:

```text
> 1^M
```

This is caused by the terminal line discipline, not by the filtered environment
variables. The affected terminal is consistent with this termios combination:

```text
icanon -icrnl echoctl
```

With canonical input enabled, the kernel holds input until it sees a configured
line delimiter. A terminal normally sends carriage return (`CR`, byte 13) for
Enter, and `icrnl` translates it to line feed (`LF`, byte 10). When `icrnl` is
disabled, CR is echoed as `^M` by `echoctl`, but it does not complete the
canonical line. Consequently, Jolt has no byte available to return to
`read-line` or `System/in`.

An application-level loop that treats both CR and LF as terminators is therefore
insufficient in this mode: the CR remains inside the kernel's canonical input
buffer and never reaches that loop.

## Environment assessment

Variables such as these do not control terminal input translation:

```text
SHELL=/usr/bin/zsh
TERM=xterm-kitty
GPG_TTY=/dev/pts/9
JAI_MODE=strict
JAI_JAIL=code
```

Out`SHELL` does not need to match the currently running shell, `TERM` describes
terminal capabilities, and `GPG_TTY` is used by GnuPG rather than as the
process's stdin. The JAI variables identify the sandbox, but termios flags are
properties of the open tty device and are not environment variables.

Inspect the relevant state from the exact affected shell with:

```bash
tty
stty -a
```

The significant flags are `icanon` and `-icrnl`. The visible `^M` is strong
evidence for disabled CR-to-LF translation when `echoctl` is enabled.

## Self-contained reproduction

Requirements: a POSIX tty, `stty`, GNU Expect, and a Jolt executable. Save this
as `/tmp/jolt-icrnl.expect`:

```tcl
#!/usr/bin/expect -f
set timeout 5
set jolt [expr {$argc ? [lindex $argv 0] : "jolt"}]

proc stop-child {} {
  catch {exec kill -KILL [exp_pid]}
  catch {wait}
}

puts "CONTROL: canonical tty with icrnl enabled"
spawn -noecho sh -c "stty icanon icrnl; exec '$jolt' -e '(do (println \"READY\") (println (pr-str (read-line))))'"
expect "READY"
send -- "1\r"
set timeout 2
expect {
  -re {"1"} {puts "PASS: Enter completed the line"}
  timeout {
    puts stderr "FAIL: normal Enter remained blocked"
    stop-child
    exit 1
  }
}
stop-child

puts "REPRO: canonical tty with icrnl disabled"
set timeout 5
spawn -noecho sh -c "stty icanon -icrnl; exec '$jolt' -e '(do (println \"READY\") (println (pr-str (read-line))))'"
expect "READY"
send -- "1\r"
set timeout 2
expect {
  -re {"1"} {
    puts stderr "FAIL: CR unexpectedly completed the canonical line"
    stop-child
    exit 1
  }
  timeout {
    puts "REPRODUCED: Enter was echoed as ^M and read-line stayed blocked"
  }
}
stop-child
```

Run it with:

```bash
chmod +x /tmp/jolt-icrnl.expect
expect /tmp/jolt-icrnl.expect /path/to/jolt
```

Expected output includes:

```text
CONTROL: canonical tty with icrnl enabled
READY
1
"1"
PASS: Enter completed the line
REPRO: canonical tty with icrnl disabled
READY
1^MREPRODUCED: Enter was echoed as ^M and read-line stayed blocked
```

This is deterministic and does not require the proxy project, OAuth, network
access, or a browser.

## Test against the latest Jolt release

Tested on 2026-08-20 with the latest GitHub release:

```text
jolt v0.7.17
asset: jolt-v0.7.17-x86_64-linux.tar.gz
sha256: c811d9845e91ef765d33e36d5982cddf093e1003c32ca690f5e88d6b5116a9a0
```

The self-contained reproduction produced the expected control result and the
expected `1^M` timeout under `icanon -icrnl`. This is normal POSIX terminal
behavior rather than a Jolt `read-line` defect: no userspace runtime can read a
canonical line that the kernel has not released.

The proxy's repaired end-to-end login test also passed under Jolt v0.7.17. It
started the CLI with `icanon -icrnl`, sent `1` plus CR, and observed browser
login start and print the authorization URL.

## Application workaround

Before displaying an interactive line prompt, restore the standard Enter
translation on inherited stdin:

```clojure
(let [process (-> (ProcessBuilder. ["stty" "icrnl"])
                  (.redirectInput ProcessBuilder$Redirect/INHERIT)
                  (.start))]
  (.waitFor process))
```

The repair must finish **before** the prompt is printed. Printing the prompt
first creates a race: an automated input relay can send CR before `stty` changes
the tty, leaving that already-buffered CR untranslated and the read blocked.

The project retains a CR/LF-aware reader as a second layer for noncanonical
terminals and PTY relays where CR is delivered directly to userspace.

Jolt v0.7.17 still does not implement the standard
`ProcessBuilder.inheritIO()` convenience method:

```text
No matching field found: inheritIO for class java.lang.ProcessBuilder
```

Using `redirectInput` with `ProcessBuilder$Redirect/INHERIT` is sufficient for
`stty`, because `tcsetattr` operates on its stdin tty. It also avoids opening
`/dev/tty`, which can be restricted independently inside a jail.

## Go comparison

The reference Go CLI reads the choice with `fmt.Scanln`. It does not explicitly
repair termios state. Under a deliberately forced `icanon -icrnl` tty, the
current `tmp-go-bin` comparison binary also remained blocked after `1^M`, as
expected from the kernel behavior.

If the Go binary succeeds in a shell where the Jolt command previously failed,
compare `stty -a` immediately before both launches. Either the tty was restored
between commands or the input relay supplied LF in the successful run. The
environment listing alone cannot establish that both processes received the
same tty mode and byte sequence.

## Shell recovery

After interrupting the blocked command, repair only the relevant flag with:

```bash
stty icrnl
```

Or restore a broader set of conventional interactive terminal settings with:

```bash
stty sane
```

The focused `stty icrnl` command is preferable when other intentional tty
settings should be preserved.
