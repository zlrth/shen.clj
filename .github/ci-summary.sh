#!/usr/bin/env bash
# Builds a table of test counts from the logs left in logs/ by the test steps
# and appends it to the GitHub Actions run summary, where it is kept with the
# run rather than buried in the job output.
#
# Writes to stdout when GITHUB_STEP_SUMMARY is unset, so it can be checked by
# hand:  .github/ci-summary.sh "Local run"
set -euo pipefail

title=${1:-Test results}
out=${GITHUB_STEP_SUMMARY:-/dev/stdout}

# shen.test prints one of these per suite; see test/shen/test.clj.
shen_rows=$(grep -h '^SHEN-RESULT' logs/*.log 2>/dev/null |
	sed -E 's/^SHEN-RESULT suite=([^ ]+) passed=([0-9]+) failed=([0-9]+).*/| \1 | \2 | \3 |/' || true)

# clojure.test reports in its own format: "Ran N tests containing M
# assertions." followed by "X failures, Y errors.". Assertions are the
# comparable unit, so a failure count comes out of the second line.
clojure_row=
if [ -f logs/clojure.log ]; then
	assertions=$(sed -nE 's/^Ran [0-9]+ tests containing ([0-9]+) assertions\.$/\1/p' \
		logs/clojure.log | tail -1)
	counts=$(sed -nE 's/^([0-9]+) failures, ([0-9]+) errors\.$/\1 \2/p' \
		logs/clojure.log | tail -1)
	if [ -n "$assertions" ] && [ -n "$counts" ]; then
		bad=$(echo "$counts" | awk '{print $1 + $2}')
		clojure_row="| clojure | $((assertions - bad)) | $bad |"
	fi
fi

{
	echo "### $title"
	echo
	echo "| Suite | Passed | Failed |"
	echo "| --- | ---: | ---: |"
	if [ -z "$shen_rows" ] && [ -z "$clojure_row" ]; then
		# Distinguishable from a clean run: no counts at all means the suites
		# did not get far enough to report, which is not the same as passing.
		echo "| **no results reported** | – | – |"
	else
		[ -n "$clojure_row" ] && echo "$clojure_row"
		[ -n "$shen_rows" ] && echo "$shen_rows"
	fi
	echo
	echo "<sub>Counts as reported by each suite's own harness.</sub>"
} >>"$out"
