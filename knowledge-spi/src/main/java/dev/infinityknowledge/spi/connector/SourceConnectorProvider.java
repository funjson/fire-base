package dev.infinityknowledge.spi.connector;

/**
 * Opens external source connectors of one stable type.
 *
 * <p>The application layer receives providers as a list and selects one by
 * {@link #type()}; implementations remain in their connector adapter module.</p>
 */
public interface SourceConnectorProvider {

    /**
     * Stable connector type handled by this provider.
     *
     * @return type such as {@code OBSIDIAN}
     */
    String type();

    /**
     * Opens a read-only connector for the supplied persisted definition.
     *
     * @param definition active connector definition
     * @return source connector
     */
    SourceConnector open(SourceConnectorDefinition definition);
}
