# Product

## Register

product

## Users

Engineering managers, team leads, and HR/ops at an org that has rolled out AI coding tools
(Cursor, Claude Code, Codex, etc.). They open the AIWatch console on a desktop to answer
"how deeply / how well / how productively is the team using AI?" — drilling from org-wide
dashboards into per-employee and per-project detail, individual AI sessions, and LLM-scored
analysis reports. They are reading data, comparing people and projects, and configuring
collection — not casual browsing. Employees themselves never log in.

## Product Purpose

A monitoring & insight console for AI-assisted development. Surfaces AI penetration/dependency,
session health, model/tool usage, git output, and watchlist anomalies. Success = a manager
trusts the numbers and can find the signal (who's stuck, who's a power user, which project is
AI-heavy) in seconds, without fighting the interface.

## Brand Personality

Calm, precise, data-dense, trustworthy. The instrument disappears into the task — closer to
Linear/Stripe dashboards than to a marketing surface. Information density is a feature; clarity
and consistency beat flourish.

## Anti-references

Not a marketing/landing aesthetic (no hero metrics-as-decoration, no gradient-text, no oversized
display type). Not a sprawling consumer dashboard with mismatched widgets. No horizontal scroll
bleaking out of the app shell; no content that breaks its container when data is long or windows
are narrow.

## Design Principles

- **Nothing escapes its container.** Long unbreakable strings (repo URLs, file paths, model names),
  wide tables, and rich message content stay inside their bounds at any desktop width — overflow is
  a bug, not a layout strategy.
- **Consistency is an affordance.** One spacing scale, one table vocabulary, one set of state colors
  across every page. Same shape means same meaning.
- **Density with rhythm.** Data-dense is right for this product, but tight groupings need generous
  separation between them so the eye can parse the structure.
- **Structural responsiveness, not fluid type.** Adapt by collapsing/scrolling/reflowing regions at
  desktop widths; keep a fixed, readable type scale.

## Accessibility & Inclusion

Internal desktop tool. Body text ≥ 4.5:1 contrast; visible focus states; respects
`prefers-reduced-motion`. Status is never conveyed by color alone (pair with label/icon).
