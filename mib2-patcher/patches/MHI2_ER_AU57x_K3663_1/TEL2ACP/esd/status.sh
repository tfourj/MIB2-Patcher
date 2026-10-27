#!/bin/sh
# GEM entry: TEL2ACP status
# Runs on the RCC like the M.I.B. scripts do

on -f rcc /net/mmx/fs/sda0/custom-patches/TEL2ACP/tel2acp.sh status

# GEM closes the output when the script ends, so leave time to read it
sleep 10
