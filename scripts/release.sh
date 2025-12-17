#!/bin/bash

# Release script for Maven Central publishing
# Usage: ./scripts/release.sh

set -e

echo "Starting Maven Central release process..."

# Verify we're on main branch
CURRENT_BRANCH=$(git branch --show-current)
if [ "$CURRENT_BRANCH" != "main" ]; then
    echo "Error: Must be on main branch for release. Current branch: $CURRENT_BRANCH"
    exit 1
fi

# Verify working directory is clean
if [ -n "$(git status --porcelain)" ]; then
    echo "Error: Working directory is not clean. Please commit or stash changes."
    git status --short
    exit 1
fi

# Verify tests pass
echo "Running tests..."
mvn clean test -q

# Verify GPG setup
echo "Checking GPG configuration..."
if ! gpg --list-secret-keys | grep -q "sec"; then
    echo "Error: No GPG secret keys found. Please set up GPG signing."
    echo "See: https://central.sonatype.org/publish/requirements/gpg/"
    exit 1
fi

# Build and deploy
echo "Building and deploying release artifacts..."
mvn clean deploy -DperformRelease=true

echo ""
echo "Release process complete!"
echo ""
echo "Next steps:"
echo "1. Log into https://central.sonatype.com/"
echo "2. Check the deployment status"
echo "3. If autoPublish is disabled, manually release the staging repository"
echo ""
echo "For more information, see:"
echo "https://central.sonatype.org/publish/release/"
