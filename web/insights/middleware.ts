// Route gate: the insights shell requires a session. Unauthenticated requests to
// a protected page are redirected to /login (with ?next= to bounce back);
// already-authenticated requests to /login are sent on to /ask.
//
// This only checks for the PRESENCE of the httpOnly session cookie — the BFF
// still validates the token on every backend call, so a forged/expired cookie
// gets a real 401 there. The middleware is UX, not the security boundary.

import { NextRequest, NextResponse } from "next/server";

const COOKIE = process.env.CADENCE_INSIGHTS_COOKIE || "cadence_insights_session";

const PUBLIC_PREFIXES = ["/login"];

function isPublic(pathname: string): boolean {
  return PUBLIC_PREFIXES.some((p) => pathname === p || pathname.startsWith(p + "/"));
}

export function middleware(req: NextRequest) {
  const { pathname } = req.nextUrl;
  const hasSession = Boolean(req.cookies.get(COOKIE)?.value);

  if (isPublic(pathname)) {
    if (hasSession && pathname === "/login") {
      return NextResponse.redirect(new URL("/ask", req.url));
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

export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico).*)"],
};
