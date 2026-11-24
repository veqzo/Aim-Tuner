# 0007: Manual, voluntary training data contributions on GitHub

## Context
The models in AimTune (the simulated hand, the score weights, the start of a search) are tuned on simulated
players and on the recordings of two people. More real rounds would help. The research export (docs/RESEARCH-EXPORT.md)
already writes all rounds into one file, but a player has to know where to send it.

A collection service would need a server, an account system or a third-party form. Each of these adds operation,
data processing on the maintainer's side and legal duties. Under German law a public service can need a contact
address (Impressum) and a data protection notice; GitHub hosting or a non-commercial project does not remove these
duties by itself. The maintainer does not want to publish a home address. These questions are not settled.

## Decision
- AimTune never uploads anything. A player can create the file, show it in Explorer and open a prefilled GitHub
  issue in the existing repository, each step after a click. The player attaches the file or links a copy in their
  own account. There is no server, token, OAuth, GitHub App, HTTP request or telemetry, and no separate data repository.
- The issue link holds the app and format versions and counts only, URL-encoded; never measurements or paths.
- The export format 2 drops the round seeds (they showed the PC's uptime) and the date in the file name.
- The texts say that sharing is voluntary, that an issue is public and shows the GitHub account, and that copies can
  stay after a deletion. They never call the data anonymous. AimTune keeps no link between accounts and datasets.
- The feature is temporary and off: a build switch (`aimtune.contribution.enabled=false`) hides it, and the issue
  form was first kept in `docs/contribution/`. On 2026-11-24 the maintainer moved it to `.github/ISSUE_TEMPLATE/`
  and created the `training-data` label; the form is live once it is on `main`. The maintainer switched the app on
  for v1.1.1 on the same day; the legal questions are still open.

## Consequences
- The local export works as before; the contribution code sits in `app/contribute` and can be removed in one step
  (docs/RESEARCH-EXPORT.md lists the parts).
- Contributions are public. Anyone can download them, and the maintainer cannot delete copies others made.
- Contributors need a GitHub account. That limits the number of contributions, which is accepted for a first collection.
- GitHub does not state the maximum URL length; the app keeps links under 6000 characters and drops the summary if needed.
- Not tested by a person yet: Explorer with a path that contains spaces, and the issue form on GitHub (it is not active).
