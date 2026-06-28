import { Step } from "@/components/install/step";
import { CodeBlock } from "@/components/ui/code-block";
import { DeviceCodeCard } from "@/components/install/device-code-card";

// P2-E.7 — install instructions (daemon + extensions) with a device-enrollment
// code so the daemon adopts the member's identity (no painful login).
export default function InstallPage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Install Cadence</h1>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
          Set up the background agent on your machine, then connect your editor
          and browser. Takes a couple of minutes. Raw activity stays on your
          device — only what your org&apos;s privacy level allows is shared.
        </p>
      </div>

      <Step n={1} title="Install the background agent">
        <p>
          The Cadence agent runs quietly in the background and keeps your raw
          activity in an encrypted local store. Install it for your platform:
        </p>
        <p>
          <span className="font-medium text-slate-700 dark:text-slate-200">
            macOS / Linux
          </span>{" "}
          — run the installer (sets up a login/user service that survives
          logout):
        </p>
        <CodeBlock>curl -fsSL https://get.cadence.dev/install.sh | sh</CodeBlock>
        <p>
          <span className="font-medium text-slate-700 dark:text-slate-200">
            Windows
          </span>{" "}
          — download and run the Cadence installer, which registers the agent as
          a background service.
        </p>
      </Step>

      <Step n={2} title="Connect this device">
        <p>
          Generate a one-time enrollment code and paste it into the agent when it
          asks. This links the agent to your account so all your collectors share
          one identity — no separate login.
        </p>
        <DeviceCodeCard />
        <p className="text-xs text-slate-500 dark:text-slate-400">
          When prompted by the agent, run its enroll step and paste the code:
        </p>
        <CodeBlock>cadence enroll &lt;code&gt;</CodeBlock>
      </Step>

      <Step n={3} title="Add the editor & browser extensions">
        <p>
          The extensions attribute editor and browser time to the right project.
          They&apos;re optional but make your timeline far more accurate.
        </p>
        <ul className="list-disc space-y-1 pl-5">
          <li>
            <span className="font-medium text-slate-700 dark:text-slate-200">
              VS Code
            </span>{" "}
            — install the “Cadence” extension from the Marketplace. It talks to
            the local agent automatically.
          </li>
          <li>
            <span className="font-medium text-slate-700 dark:text-slate-200">
              Chrome
            </span>{" "}
            — add the “Cadence” extension from the Web Store. By default it
            records domains only, never full URLs.
          </li>
        </ul>
        <p className="text-xs text-slate-500 dark:text-slate-400">
          Once the agent is running and enrolled, your data appears on the team
          overview within a few minutes.
        </p>
      </Step>
    </div>
  );
}
