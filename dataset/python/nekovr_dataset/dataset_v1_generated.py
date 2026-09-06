"""Generated binding surface for ``nekovr_dataset_v1.fbs``.

This module intentionally has no runtime dependency for conformance and archive
inspection. Field ordinals are generated from the append-only v1 schema.
"""

from __future__ import annotations

from dataclasses import dataclass
import struct

IDENTIFIER = b"NVRD"
FILE_HEADER = 0
TRACKER_ROSTER = 1
FRAME_BATCH = 2
EVENT_BATCH = 3
FOOTER = 4


class Table:
    def __init__(self, data: bytes, position: int):
        self.data = data
        self.position = position

    def _field(self, index: int) -> int | None:
        vtable = self.position - struct.unpack_from("<i", self.data, self.position)[0]
        length = struct.unpack_from("<H", self.data, vtable)[0]
        entry = vtable + 4 + index * 2
        if entry + 2 > vtable + length:
            return None
        offset = struct.unpack_from("<H", self.data, entry)[0]
        return None if offset == 0 else self.position + offset

    def u8(self, index: int, default: int = 0) -> int:
        field = self._field(index)
        return default if field is None else self.data[field]

    def u16(self, index: int, default: int = 0) -> int:
        field = self._field(index)
        return default if field is None else struct.unpack_from("<H", self.data, field)[0]

    def u32(self, index: int, default: int = 0) -> int:
        field = self._field(index)
        return default if field is None else struct.unpack_from("<I", self.data, field)[0]

    def u64(self, index: int, default: int = 0) -> int:
        field = self._field(index)
        return default if field is None else struct.unpack_from("<Q", self.data, field)[0]

    def f32(self, index: int, default: float = 0.0) -> float:
        field = self._field(index)
        return default if field is None else struct.unpack_from("<f", self.data, field)[0]

    def string(self, index: int) -> str | None:
        field = self._field(index)
        if field is None:
            return None
        start = field + struct.unpack_from("<I", self.data, field)[0]
        length = struct.unpack_from("<I", self.data, start)[0]
        return self.data[start + 4 : start + 4 + length].decode("utf-8")

    def table(self, index: int) -> "Table | None":
        field = self._field(index)
        return None if field is None else Table(self.data, field + struct.unpack_from("<I", self.data, field)[0])

    def vector_length(self, index: int) -> int:
        field = self._field(index)
        if field is None:
            return 0
        vector = field + struct.unpack_from("<I", self.data, field)[0]
        return struct.unpack_from("<I", self.data, vector)[0]

    def vector_table(self, index: int, element: int) -> "Table":
        field = self._field(index)
        if field is None:
            raise IndexError("missing vector")
        vector = field + struct.unpack_from("<I", self.data, field)[0]
        length = struct.unpack_from("<I", self.data, vector)[0]
        if element < 0 or element >= length:
            raise IndexError(element)
        slot = vector + 4 + element * 4
        return Table(self.data, slot + struct.unpack_from("<I", self.data, slot)[0])

    def vector_string(self, index: int, element: int) -> str:
        field = self._field(index)
        if field is None:
            raise IndexError("missing vector")
        vector = field + struct.unpack_from("<I", self.data, field)[0]
        length = struct.unpack_from("<I", self.data, vector)[0]
        if element < 0 or element >= length:
            raise IndexError(element)
        slot = vector + 4 + element * 4
        start = slot + struct.unpack_from("<I", self.data, slot)[0]
        str_len = struct.unpack_from("<I", self.data, start)[0]
        return self.data[start + 4 : start + 4 + str_len].decode("utf-8")


@dataclass(frozen=True)
class DatasetRecord:
    data: bytes
    root: Table

    @classmethod
    def parse(cls, data: bytes) -> "DatasetRecord":
        if len(data) < 8 or data[4:8] != IDENTIFIER:
            raise ValueError("invalid NVRD FlatBuffer identifier")
        root = struct.unpack_from("<I", data, 0)[0]
        return cls(data, Table(data, root))

    @property
    def record_type(self) -> int:
        return self.root.u8(0)

    @property
    def sequence(self) -> int:
        return self.root.u64(1)

    @property
    def header(self) -> Table | None:
        return self.root.table(2)

    @property
    def frame_batch(self) -> Table | None:
        return self.root.table(4)

    @property
    def roster(self) -> Table | None:
        return self.root.table(3)

    @property
    def event_batch(self) -> Table | None:
        return self.root.table(5)

    @property
    def footer(self) -> Table | None:
        return self.root.table(6)


def half(table: Table | None, index: int, default: float = 0.0) -> float:
    if table is None:
        return default
    return struct.unpack("<e", struct.pack("<H", table.u16(index)))[0]


def float_quat(table: Table | None) -> tuple[float, float, float, float]:
    if table is None:
        return (0.0, 0.0, 0.0, 1.0)
    return (table.f32(0), table.f32(1), table.f32(2), table.f32(3, 1.0))
