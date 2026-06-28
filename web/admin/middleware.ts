// Route gate: the admin shell requires a session. Unauthenticated requests to a
// protected page are redirected to /login (with a ?next= so we can bounce back);
// already-authenticated requests to an auth page are sent on to the overview.
//
// This only checks for the PRESENCE of the httpOnly session cookie — the BFF
// still validates the token on every backend call, so a forged/expired cookie
// gets a real 401 there. The middleware is UX, not the security boundary.

import { NextRequest, NextResponse } from "next/server";

const COOKIE = process.env.CADENCE_ADMIN_COOKIE || "cadence_admin_session";

// Pages that must NOT require a session.
const PUBLIC_PREFIXES = ["/login", "/register", "/invite"];

function isPublic(pathname: string): boolean {
  return PUBLIC_PREFIXES.some(
    (p) => pathname === p || pathname.startsWith(p + "/"),
  );
}

export function middleware(req: NextRequest) {
  const { pathname } = req.nextUrl;
  const hasSession = Boolean(req.cookies.get(COOKIE)?.value);

  if (isPublic(pathname)) {
    // Skip auth pages straight to the app if already signed in.
    if (hasSession && (pathname === "/login" || pathname === "/register")) {
      return NextResponse.redirect(new URL("/overview", req.url));
    }
    return NextResponse.next();
  }

  if (!hasSession) {
    const url = new URL("/login", req.url);
    if (pathname !== "/") url.searchParams.set("next", pathname);
    return NextResponse.redirect(url);
  }
  return NextResponse.next();
}

// Run on everything except Next internals, the BFF API, and static assets.
export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico).*)"],
};
