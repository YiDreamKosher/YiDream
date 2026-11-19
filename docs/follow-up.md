# Google sign-in branding

Reported 19 November 2026: Google's account chooser shows the Supabase project host rather than YiDream. Sign-in itself works; this is an identity and trust presentation follow-up.

Configure the YiDream name, official logo, public home page and privacy page in Google Auth Platform → Branding, then complete Google's brand verification. Verification can take several business days. Do not change a working OAuth client or callback merely to hide the hostname.

The live Google chooser also still links to the old `qinfrance.github.io/YiDream-Suite/` home page for both Privacy and Terms. Replace those entries with the current public home, dedicated privacy page and terms page during branding verification.

When a YiDream domain is available, consider a Supabase custom auth subdomain such as `auth.<owned-domain>`. Confirm ownership, available plan and cost first. Add its callback to Google before switching application configuration, retain the old callback during the transition and test sign-in/sign-out with a fresh session before removing it.

Source: https://supabase.com/docs/guides/auth/social-sign-in/auth-google#setup-consent-screen-branding
