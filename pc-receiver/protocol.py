"""
MicRelay Wire Protocol Definitions (PC Receiver)
"""

import struct
from dataclasses import dataclass
from typing import Optional

MAGIC = b'MR'  # 0x4D, 0x52
VERSION = 1

PAYLOAD_TYPE_HEARTBEAT = 0
PAYLOAD_TYPE_OPUS = 1
PAYLOAD_TYPE_PCM = 2
PAYLOAD_TYPE_HANDSHAKE = 3
PAYLOAD_TYPE_ACK = 4
PAYLOAD_TYPE_DISCOVERY = 5
PAYLOAD_TYPE_DISCOVERY_ACK = 6

HEADER_FORMAT = '>2sBBIQH'
HEADER_SIZE = struct.calcsize(HEADER_FORMAT)  # 18 bytes

@dataclass
class Packet:
    version: int
    payload_type: int
    seq: int
    timestamp: int
    payload_length: int
    data: bytes

def unpack_packet(raw_bytes: bytes) -> Optional[Packet]:
    if len(raw_bytes) < HEADER_SIZE:
        return None
    
    magic, version, ptype, seq, ts, length = struct.unpack_from(HEADER_FORMAT, raw_bytes, 0)
    if magic != MAGIC or version != VERSION:
        return None
    
    if len(raw_bytes) < HEADER_SIZE + length:
        return None
        
    return Packet(
        version=version,
        payload_type=ptype,
        seq=seq,
        timestamp=ts,
        payload_length=length,
        data=raw_bytes[HEADER_SIZE:HEADER_SIZE + length]
    )

def pack_packet(seq: int, timestamp: int, payload_type: int, data: bytes) -> bytes:
    header = struct.pack(
        HEADER_FORMAT,
        MAGIC,
        VERSION,
        payload_type,
        seq,
        timestamp,
        len(data)
    )
    return header + data
