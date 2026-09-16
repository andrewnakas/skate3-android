/* Structural ELF test fixture only; never loaded as a Vulkan driver. */
__attribute__((visibility("default"))) int driver_regression_fixture(void) {
    return 7;
}
