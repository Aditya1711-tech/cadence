import { redirect } from "next/navigation";

// The shell lives at /overview; the middleware bounces unauthenticated callers
// to /login before this ever renders.
export default function Home() {
  redirect("/overview");
}
