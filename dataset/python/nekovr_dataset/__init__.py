"""Dependency-light reader for the canonical NekoVR dataset contract."""

from .reader import DatasetReader, DatasetFormatError, footer_checksum

__all__ = ["DatasetReader", "DatasetFormatError", "footer_checksum"]
