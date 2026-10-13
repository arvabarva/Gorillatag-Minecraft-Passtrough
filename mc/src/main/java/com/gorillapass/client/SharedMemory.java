package com.gorillapass.client;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

/**
 * A named Windows shared-memory block (backed by the page file, never written to disk), created through Java's
 * foreign function API. Gorilla Tag opens the same name and reads Minecraft's frames straight out of it.
 */
final class SharedMemory {
	private static final int PAGE_READWRITE = 0x04;
	private static final int FILE_MAP_ALL_ACCESS = 0xF001F;
	private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();
	private static final VarHandle LONG = ValueLayout.JAVA_LONG.varHandle();

	final MemorySegment segment;

	private SharedMemory(MemorySegment segment) {
		this.segment = segment;
	}

	static SharedMemory create(String name, long size) throws Throwable {
		Linker linker = Linker.nativeLinker();
		SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
		MethodHandle createFileMapping = linker.downcallHandle(kernel32.findOrThrow("CreateFileMappingW"),
			FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
		MethodHandle mapViewOfFile = linker.downcallHandle(kernel32.findOrThrow("MapViewOfFile"),
			FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));

		MemorySegment wideName = Arena.global().allocateFrom(name, StandardCharsets.UTF_16LE);
		MemorySegment invalidHandle = MemorySegment.ofAddress(-1L);
		MemorySegment handle = (MemorySegment) createFileMapping.invokeExact(invalidHandle, MemorySegment.NULL, PAGE_READWRITE,
			(int) (size >>> 32), (int) size, wideName);
		if (handle.address() == 0L) {
			throw new IllegalStateException("CreateFileMappingW failed for " + name);
		}
		MemorySegment view = (MemorySegment) mapViewOfFile.invokeExact(handle, FILE_MAP_ALL_ACCESS, 0, 0, size);
		if (view.address() == 0L) {
			throw new IllegalStateException("MapViewOfFile failed for " + name);
		}
		// the mapping handle stays open for as long as Minecraft runs, so Gorilla Tag can always find it
		return new SharedMemory(view.reinterpret(size));
	}

	int getIntVolatile(long offset) {
		return (int) INT.getVolatile(this.segment, offset);
	}

	void setIntVolatile(long offset, int value) {
		INT.setVolatile(this.segment, offset, value);
	}

	void setLongVolatile(long offset, long value) {
		LONG.setVolatile(this.segment, offset, value);
	}

	long getLongVolatile(long offset) {
		return (long) LONG.getVolatile(this.segment, offset);
	}
}
