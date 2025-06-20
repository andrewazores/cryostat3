/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.events;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.openjdk.jmc.common.unit.IConstrainedMap;
import org.openjdk.jmc.common.unit.SimpleConstrainedMap;
import org.openjdk.jmc.common.unit.UnitLookup;
import org.openjdk.jmc.flightrecorder.configuration.events.EventConfiguration;
import org.openjdk.jmc.flightrecorder.configuration.events.EventOptionID;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.JFCGrammar;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLAttributeInstance;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLModel;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLTagInstance;
import org.openjdk.jmc.flightrecorder.configuration.model.xml.XMLValidationResult;

import io.cryostat.ConfigProperties;
import io.cryostat.DeclarativeConfiguration;
import io.cryostat.Producers;
import io.cryostat.StorageBuckets;
import io.cryostat.core.FlightRecorderException;
import io.cryostat.core.templates.MutableTemplateService;
import io.cryostat.libcryostat.templates.InvalidEventTemplateException;
import io.cryostat.libcryostat.templates.Template;
import io.cryostat.libcryostat.templates.TemplateType;
import io.cryostat.recordings.ArchivedRecordingMetadataService;
import io.cryostat.recordings.RecordingHelper;
import io.cryostat.ws.MessagingServer;
import io.cryostat.ws.Notification;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Identifier;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

/**
 * Event Template service implementation for Custom Event Templates. Custom Event Templates are ones
 * that users can create and delete at runtime. These must be in conventional .jfc XML format. This
 * implementation uses an S3 object storage service to house the XML files.
 */
@ApplicationScoped
public class S3TemplateService implements MutableTemplateService {

    static final String EVENT_TEMPLATE_CREATED = "TemplateUploaded";
    static final String EVENT_TEMPLATE_DELETED = "TemplateDeleted";

    private static final String META_KEY_NAME = "label";
    private static final String META_KEY_DESCRIPTION = "description";
    private static final String META_KEY_PROVIDER = "provider";

    @ConfigProperty(name = ConfigProperties.AWS_BUCKET_NAME_EVENT_TEMPLATES)
    String bucket;

    @ConfigProperty(name = ConfigProperties.STORAGE_METADATA_EVENT_TEMPLATES_STORAGE_MODE)
    String metadataStorageMode;

    @ConfigProperty(name = ConfigProperties.CUSTOM_TEMPLATES_DIR)
    Path dir;

    @Inject DeclarativeConfiguration declarativeConfiguration;
    @Inject S3Client storage;
    @Inject StorageBuckets storageBuckets;
    @Inject Instance<BucketedEventTemplateMetadataService> metadataService;

    @Inject EventBus bus;

    @Inject
    @Identifier(Producers.BASE64_URL)
    Base64 base64Url;

    @Inject Logger logger;

    void onStart(@Observes StartupEvent evt) {
        storageBuckets
                .createIfNecessary(bucket)
                .thenRunAsync(
                        () -> {
                            try {
                                declarativeConfiguration
                                        .walk(dir)
                                        .forEach(
                                                p -> {
                                                    try (var is =
                                                            new BufferedInputStream(
                                                                    Files.newInputStream(p))) {
                                                        logger.debugv(
                                                                "Uploading template from {0} to S3",
                                                                p.toString());
                                                        addTemplate(is);
                                                    } catch (IOException
                                                            | InvalidXmlException
                                                            | InvalidEventTemplateException e) {
                                                        logger.error(e);
                                                    } catch (IllegalArgumentException e) {
                                                        logger.warn(e);
                                                    }
                                                });
                            } catch (IOException e) {
                                logger.error(e);
                            }
                        });
    }

    @Override
    public Optional<IConstrainedMap<EventOptionID>> getEvents(
            String templateName, TemplateType unused) throws FlightRecorderException {
        try (var stream = getModel(templateName)) {
            return Optional.of(
                    new EventConfiguration(parseXml(stream))
                            .getEventOptions(
                                    new SimpleConstrainedMap<>(
                                            UnitLookup.PLAIN_TEXT.getPersister())));
        } catch (IOException | ParseException e) {
            logger.error(e);
            return Optional.empty();
        }
    }

    @Override
    public List<Template> getTemplates() throws FlightRecorderException {
        return getObjects().stream()
                .map(
                        t -> {
                            try {
                                return convertObject(t);
                            } catch (InvalidEventTemplateException | IOException e) {
                                logger.error(e);
                                return null;
                            }
                        })
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Optional<String> getXml(String templateName, TemplateType unused)
            throws FlightRecorderException {
        try (var stream = getModel(templateName)) {
            return Optional.of(
                    Jsoup.parse(stream, StandardCharsets.UTF_8.name(), "", Parser.xmlParser())
                            .outerHtml());
        } catch (IOException e) {
            logger.error(e);
            return Optional.empty();
        }
    }

    private ArchivedRecordingMetadataService.StorageMode storageMode() {
        return RecordingHelper.storageMode(metadataStorageMode);
    }

    private List<S3Object> getObjects() {
        var builder = ListObjectsV2Request.builder().bucket(bucket);
        return storage.listObjectsV2(builder.build()).contents();
    }

    private Template convertObject(S3Object object)
            throws InvalidEventTemplateException, IOException {
        String label, description, provider;

        switch (storageMode()) {
            case TAGGING:
                var getReq =
                        GetObjectTaggingRequest.builder().bucket(bucket).key(object.key()).build();
                var tagging = storage.getObjectTagging(getReq);
                var list = tagging.tagSet();
                if (!tagging.hasTagSet() || list.isEmpty()) {
                    throw new InvalidEventTemplateException("No metadata found");
                }
                var decodedList = new ArrayList<Pair<String, String>>();
                list.forEach(
                        t -> {
                            var encodedKey = t.key();
                            var decodedKey =
                                    new String(base64Url.decode(encodedKey), StandardCharsets.UTF_8)
                                            .trim();
                            var encodedValue = t.value();
                            var decodedValue =
                                    new String(
                                                    base64Url.decode(encodedValue),
                                                    StandardCharsets.UTF_8)
                                            .trim();
                            decodedList.add(Pair.of(decodedKey, decodedValue));
                        });
                label =
                        decodedList.stream()
                                .filter(t -> t.getKey().equals(META_KEY_NAME))
                                .map(Pair::getValue)
                                .findFirst()
                                .orElseThrow();
                description =
                        decodedList.stream()
                                .filter(t -> t.getKey().equals(META_KEY_DESCRIPTION))
                                .map(Pair::getValue)
                                .findFirst()
                                .orElseThrow();
                provider =
                        decodedList.stream()
                                .filter(t -> t.getKey().equals(META_KEY_PROVIDER))
                                .map(Pair::getValue)
                                .findFirst()
                                .orElseThrow();
                break;
            case METADATA:
                var headReq = HeadObjectRequest.builder().bucket(bucket).key(object.key()).build();
                var meta = storage.headObject(headReq).metadata();
                label = Objects.requireNonNull(meta.get(META_KEY_NAME));
                description = Objects.requireNonNull(meta.get(META_KEY_DESCRIPTION));
                provider = Objects.requireNonNull(meta.get(META_KEY_PROVIDER));
                break;
            case BUCKET:
                var t = metadataService.get().read(object.key()).orElseThrow();
                label = t.getName();
                description = t.getDescription();
                provider = t.getProvider();
                break;
            default:
                throw new IllegalStateException();
        }

        return new Template(label, description, provider, TemplateType.CUSTOM);
    }

    private InputStream getModel(String name) {
        var req = GetObjectRequest.builder().bucket(bucket).key(name).build();
        return new BufferedInputStream(storage.getObject(req));
    }

    @Override
    public Template addTemplate(InputStream stream)
            throws InvalidXmlException, InvalidEventTemplateException, IOException {
        try (stream) {
            var model = parseXml(stream);
            var template = createTemplate(model);
            var existing = getTemplates();
            if (existing.stream().anyMatch(t -> Objects.equals(t.getName(), template.getName()))) {
                throw new DuplicateTemplateException(template.getName());
            }
            var reqBuilder =
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(template.getName())
                            .contentType(MediaType.APPLICATION_XML);
            switch (storageMode()) {
                case TAGGING:
                    var map =
                            Map.of(
                                    META_KEY_NAME, template.getName(),
                                    META_KEY_DESCRIPTION, template.getDescription(),
                                    META_KEY_PROVIDER, template.getProvider());
                    var tagging =
                            Tagging.builder()
                                    .tagSet(
                                            map.entrySet().stream()
                                                    .map(
                                                            e ->
                                                                    Tag.builder()
                                                                            .key(
                                                                                    base64Url
                                                                                            .encodeAsString(
                                                                                                    e.getKey()
                                                                                                            .getBytes(
                                                                                                                    StandardCharsets
                                                                                                                            .UTF_8)))
                                                                            .value(
                                                                                    base64Url
                                                                                            .encodeAsString(
                                                                                                    e.getValue()
                                                                                                            .getBytes(
                                                                                                                    StandardCharsets
                                                                                                                            .UTF_8)))
                                                                            .build())
                                                    .toList())
                                    .build();
                    reqBuilder = reqBuilder.tagging(tagging);
                    break;
                case METADATA:
                    reqBuilder =
                            reqBuilder.metadata(
                                    Map.of(
                                            META_KEY_NAME,
                                            template.getName(),
                                            META_KEY_DESCRIPTION,
                                            template.getDescription(),
                                            META_KEY_PROVIDER,
                                            template.getProvider()));
                    break;
                case BUCKET:
                    metadataService.get().create(template.getName(), template);
                    break;
                default:
                    throw new IllegalStateException();
            }
            storage.putObject(reqBuilder.build(), RequestBody.fromString(model.toString()));

            bus.publish(
                    MessagingServer.class.getName(),
                    new Notification(EVENT_TEMPLATE_CREATED, Map.of("template", template)));
            return template;
        } catch (IOException ioe) {
            // FIXME InvalidXmlException constructor should be made public in -core
            // throw new InvalidXmlException("Unable to parse XML stream", ioe);
            throw new IllegalArgumentException("Unable to parse XML stream", ioe);
        } catch (ParseException | IllegalArgumentException e) {
            throw new IllegalArgumentException(new InvalidEventTemplateException("Invalid XML", e));
        } catch (FlightRecorderException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void deleteTemplate(String templateName) {
        try {
            var template =
                    getTemplates().stream()
                            .filter(t -> t.getName().equals(templateName))
                            .findFirst()
                            .orElseThrow();
            var req = DeleteObjectRequest.builder().bucket(bucket).key(templateName).build();
            if (storage.deleteObject(req).sdkHttpResponse().isSuccessful()) {
                bus.publish(
                        MessagingServer.class.getName(),
                        new Notification(EVENT_TEMPLATE_DELETED, Map.of("template", template)));
            }
        } catch (FlightRecorderException e) {
            logger.error(e);
        }
    }

    private XMLModel parseXml(InputStream inputStream) throws IOException, ParseException {
        var model = EventConfiguration.createModel(inputStream);
        model.checkErrors();

        for (XMLValidationResult result : model.getResults()) {
            if (result.isError()) {
                throw new IllegalArgumentException(
                        new InvalidEventTemplateException(result.getText()));
            }
        }
        return model;
    }

    private Template createTemplate(XMLModel model) throws IOException, ParseException {
        XMLTagInstance configuration = model.getRoot();
        XMLAttributeInstance labelAttr = null;
        for (XMLAttributeInstance attr : configuration.getAttributeInstances()) {
            if (attr.getAttribute().getName().equals("label")) {
                labelAttr = attr;
                break;
            }
        }

        if (labelAttr == null) {
            throw new IllegalArgumentException(
                    new InvalidEventTemplateException(
                            "Template has no configuration label attribute"));
        }

        String templateName = labelAttr.getExplicitValue().replaceAll("[\\W]+", "_");

        XMLTagInstance root = model.getRoot();
        root.setValue(JFCGrammar.ATTRIBUTE_LABEL_MANDATORY, templateName);

        String description = getAttributeValue(root, "description");
        String provider = getAttributeValue(root, "provider");

        return new Template(templateName, description, provider, TemplateType.CUSTOM);
    }

    private String getAttributeValue(XMLTagInstance node, String valueKey) {
        return node.getAttributeInstances().stream()
                .filter(i -> Objects.equals(valueKey, i.getAttribute().getName()))
                .map(i -> i.getValue())
                .findFirst()
                .get();
    }

    static class DuplicateTemplateException extends IllegalArgumentException {
        DuplicateTemplateException(String templateName) {
            super(String.format("Event Template with name \"%s\" already exists", templateName));
        }
    }
}
