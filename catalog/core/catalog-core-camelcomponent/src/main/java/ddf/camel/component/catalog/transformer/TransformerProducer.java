/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package ddf.camel.component.catalog.transformer;

import ddf.camel.component.catalog.CatalogComponent;
import ddf.camel.component.catalog.CatalogEndpoint;
import ddf.catalog.transform.CatalogTransformerException;
import ddf.mime.MimeTypeToTransformerMapper;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.activation.MimeTypeParseException;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.support.DefaultProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Producer for the custom Camel CatalogComponent. This {@link org.apache.camel.Producer} would map
 * to a Camel <to> route node with a URI like <code>catalog:inputtransformer</code>
 *
 * @author Hugh Rodgers
 * @author William Miller
 */
public abstract class TransformerProducer extends DefaultProducer {

  private static final Logger LOGGER = LoggerFactory.getLogger(TransformerProducer.class);

  private CatalogEndpoint endpoint;

  private ExecutorService executor;

  private static final String TIMEOUT_HEADER_KEY = "timeoutMilliseconds";

  /**
   * Constructs the {@link org.apache.camel.Producer} for the custom Camel CatalogComponent. This
   * producer would map to a Camel <to> route node with a URI like <code>catalog:inputtransformer
   * </code>
   *
   * @param endpoint the Camel endpoint that created this consumer
   */
  public TransformerProducer(CatalogEndpoint endpoint) {
    super(endpoint);
    this.endpoint = endpoint;
    this.executor = endpoint.getExecutor();

    LOGGER.debug(
        "\"INSIDE InputTransformerProducer constructor for {}", endpoint.getTransformerId());
  }

  /*
   * (non-Javadoc)
   *
   * @see org.apache.camel.Processor#process(org.apache.camel.Exchange)
   */
  @Override
  public void process(Exchange exchange)
      throws CatalogTransformerException, MimeTypeParseException, IOException, InterruptedException,
          ExecutionException {
    LOGGER.trace("ENTERING: process");
    LOGGER.debug("exchange pattern = {}", exchange.getPattern());

    Message in = exchange.getIn();

    // Get the MIME Type and ID of the transformer to use to transform the
    // request's payload from the endpoint that manages this producer

    String transformerId = in.getHeader(CatalogComponent.ID_PARAMETER, String.class);

    String mimeType = in.getHeader(CatalogComponent.MIME_TYPE_PARAMETER, String.class);

    if (transformerId != null) {
      LOGGER.debug("transformerId retrieved from message header");
      in.removeHeader(CatalogComponent.ID_PARAMETER);
    } else {
      LOGGER.debug("transformerId retrieved from CamelCatalogEndpoint");
      transformerId = endpoint.getTransformerId();
    }

    LOGGER.debug("transformerId = {}", transformerId);

    if (mimeType != null) {
      in.removeHeader(CatalogComponent.MIME_TYPE_PARAMETER);
    } else {
      LOGGER.debug(
          "No mimeType provided, defaulting to Camel CatalogEndpoint mimeType of [{}]",
          endpoint.getMimeType());
      mimeType = endpoint.getMimeType();
    }

    LOGGER.debug("MIME Type = {}", mimeType);

    MimeTypeToTransformerMapper mapper = endpoint.getComponent().getMimeTypeToTransformerMapper();

    Object metacard;
    if (mapper != null) {
      LOGGER.debug("Got a MimeTypeToTransformerMapper service");
      Object timeoutObj = exchange.getIn().getHeader(TIMEOUT_HEADER_KEY);
      final String type = mimeType;
      final String transformId = transformerId;
      if (timeoutObj != null) {
        try {
          List<Future<Object>> futures =
              executor.invokeAll(
                  Collections.singleton(
                      (Callable<Object>) () -> transform(in, type, transformId, mapper)),
                  (long) timeoutObj,
                  TimeUnit.MILLISECONDS);
          if (!futures.get(0).isDone()) {
            LOGGER.warn("Ingest task was canceled due to timeout");
            throw new TransformerTimeoutException("The ingest request timed out 1");
          }
          metacard = futures.get(0).get();
        } catch (CancellationException e) {
          throw new TransformerTimeoutException("The ingest request timed out 2");
        }
      } else {
        metacard = transform(in, mimeType, transformerId, mapper);
      }
    } else {
      LOGGER.debug("Did not find a MimeTypeToTransformerMapper service");
      throw new CatalogTransformerException("Did not find a MimeTypeToTransformerMapper service");
    }

    // Set the body to the Metacard from the transformation
    in.setBody(metacard);

    LOGGER.trace("EXITING: process");
  }

  protected abstract Object transform(
      Message in, String mimeType, String transformerId, MimeTypeToTransformerMapper mapper)
      throws MimeTypeParseException, IOException, CatalogTransformerException;
}
