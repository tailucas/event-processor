#!/bin/bash
python "$@" 2>&1 | logger
