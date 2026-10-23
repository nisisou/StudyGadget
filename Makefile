all:
	$(MAKE) -C assets
	cp assets/studygadget.3dsx .
	cp assets/studygadget.elf .
clean:
	$(MAKE) -C assets clean
.PHONY: all clean
