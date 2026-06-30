#!/bin/bash

regex="^\[[^]]+\] .+"
commit_file="${1}"
line_number=0
error_found=0

while IFS= read -r line || [ -n "${line}" ]; do
	line_number=$((line_number + 1))

	# Allow empty lines
	if [[ -z "${line}" ]]; then
		continue
	fi

	if ! echo "${line}" | grep -Eq "${regex}"; then
		echo "Invalid commit message:"
		echo "L${line_number}: '${line}'"
		echo
		error_found=$((error_found + 1))
	fi
done <"${commit_file}"

if [[ ${error_found} -gt 0 ]]; then
	echo "Each non-empty line must follow the format: '[Category] Commit message'"
	exit 1
fi

exit 0
