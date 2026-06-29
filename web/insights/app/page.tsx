import { redirect } from "next/navigation";

// The UI lives at /ask; the middleware bounces unauthenticated callers to /login.
export default function Home() {
  redirect("/ask");
}
