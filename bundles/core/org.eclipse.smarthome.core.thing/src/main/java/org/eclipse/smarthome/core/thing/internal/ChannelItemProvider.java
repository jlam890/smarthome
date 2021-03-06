/**
 * Copyright (c) 2014-2017 by the respective copyright holders.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.smarthome.core.thing.internal;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.core.common.registry.ProviderChangeListener;
import org.eclipse.smarthome.core.common.registry.RegistryChangeListener;
import org.eclipse.smarthome.core.i18n.LocaleProvider;
import org.eclipse.smarthome.core.items.GenericItem;
import org.eclipse.smarthome.core.items.Item;
import org.eclipse.smarthome.core.items.ItemFactory;
import org.eclipse.smarthome.core.items.ItemProvider;
import org.eclipse.smarthome.core.items.ItemRegistry;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingRegistry;
import org.eclipse.smarthome.core.thing.link.ItemChannelLink;
import org.eclipse.smarthome.core.thing.link.ItemChannelLinkRegistry;
import org.eclipse.smarthome.core.thing.type.ChannelKind;
import org.eclipse.smarthome.core.thing.type.ChannelType;
import org.eclipse.smarthome.core.thing.type.TypeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class dynamically provides items for all links that point to non-existing items.
 *
 * @author Kai Kreuzer
 * @author Markus Rathgeb - Add locale provider support
 * @author Thomas Höfer - Added modified operation
 */
public class ChannelItemProvider implements ItemProvider {

    private final Logger logger = LoggerFactory.getLogger(ChannelItemProvider.class);

    private Set<ProviderChangeListener<Item>> listeners = new HashSet<>();

    private LocaleProvider localeProvider;
    private ThingRegistry thingRegistry;
    private ItemChannelLinkRegistry linkRegistry;
    private ItemRegistry itemRegistry;
    private Set<ItemFactory> itemFactories = new HashSet<>();
    private Map<String, Item> items = null;

    private boolean enabled = true;
    private boolean initialized = false;
    private long lastUpdate = System.nanoTime();

    @Override
    public Collection<Item> getAll() {
        if (!enabled || !initialized) {
            return Collections.emptySet();
        } else {
            synchronized (this) {
                if (items == null) {
                    items = new HashMap<>();
                    for (ItemChannelLink link : linkRegistry.getAll()) {
                        createItemForLink(link);
                    }
                }
            }
            return items.values();
        }
    }

    @Override
    public void addProviderChangeListener(ProviderChangeListener<Item> listener) {
        listeners.add(listener);
        for (Item item : getAll()) {
            listener.added(this, item);
        }
    }

    @Override
    public void removeProviderChangeListener(ProviderChangeListener<Item> listener) {
        listeners.remove(listener);
    }

    protected void setLocaleProvider(final LocaleProvider localeProvider) {
        this.localeProvider = localeProvider;
    }

    protected void unsetLocaleProvider(final LocaleProvider localeProvider) {
        this.localeProvider = null;
    }

    protected void addItemFactory(ItemFactory itemFactory) {
        this.itemFactories.add(itemFactory);
    }

    protected void removeItemFactory(ItemFactory itemFactory) {
        this.itemFactories.remove(itemFactory);
    }

    protected void setThingRegistry(ThingRegistry thingRegistry) {
        this.thingRegistry = thingRegistry;
    }

    protected void unsetThingRegistry(ThingRegistry thingRegistry) {
        this.thingRegistry = null;
    }

    protected void setItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    protected void unsetItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = null;
    }

    protected void setItemChannelLinkRegistry(ItemChannelLinkRegistry linkRegistry) {
        this.linkRegistry = linkRegistry;
    }

    protected void unsetItemChannelLinkRegistry(ItemChannelLinkRegistry linkRegistry) {
        this.linkRegistry = null;
    }

    protected void activate(Map<String, Object> properties) {
        modified(properties);
    }

    protected synchronized void modified(Map<String, Object> properties) {
        if (properties != null) {
            String enabled = (String) properties.get("enabled");
            if ("false".equalsIgnoreCase(enabled)) {
                this.enabled = false;
            } else {
                this.enabled = true;
            }
        }

        if (enabled) {
            Executors.newSingleThreadExecutor().submit(new Runnable() {
                @Override
                public void run() {
                    // we wait until no further new links or items are announced in order to avoid creation of
                    // items which then must be removed again immediately.
                    while (lastUpdate > System.nanoTime() - TimeUnit.SECONDS.toNanos(2)) {
                        try {
                            Thread.sleep(100L);
                        } catch (InterruptedException e) {
                        }
                    }
                    logger.debug("Enabling channel item provider.");
                    initialized = true;
                    // simply call getAll() will create the items and notify all registered listeners automatically
                    getAll();
                    addRegistryChangeListeners();
                }
            });
        } else {
            logger.debug("Disabling channel item provider.");
            for (ProviderChangeListener<Item> listener : listeners) {
                for (Item item : getAll()) {
                    listener.removed(this, item);
                }
            }
            removeRegistryChangeListeners();
        }
    }

    protected void deactivate() {
        removeRegistryChangeListeners();
        synchronized (this) {
            initialized = false;
            items = null;
        }
    }

    private void addRegistryChangeListeners() {
        this.linkRegistry.addRegistryChangeListener(linkRegistryListener);
        this.itemRegistry.addRegistryChangeListener(itemRegistryListener);
        this.thingRegistry.addRegistryChangeListener(thingRegistryListener);
    }

    private void removeRegistryChangeListeners() {
        this.itemRegistry.removeRegistryChangeListener(itemRegistryListener);
        this.linkRegistry.removeRegistryChangeListener(linkRegistryListener);
        this.thingRegistry.removeRegistryChangeListener(thingRegistryListener);
    }

    private void createItemForLink(ItemChannelLink link) {
        if (!enabled) {
            return;
        }
        if (itemRegistry.get(link.getItemName()) != null) {
            // there is already an item, we do not need to create one
            return;
        }
        Channel channel = thingRegistry.getChannel(link.getUID());
        if (channel != null) {
            Item item = null;
            // Only create an item for state channels
            if (channel.getKind() == ChannelKind.STATE) {
                for (ItemFactory itemFactory : itemFactories) {
                    item = itemFactory.createItem(channel.getAcceptedItemType(), link.getItemName());
                    if (item != null) {
                        break;
                    }
                }
            }
            if (item != null) {
                if (item instanceof GenericItem) {
                    GenericItem gItem = (GenericItem) item;
                    gItem.setLabel(getLabel(channel));
                    gItem.setCategory(getCategory(channel));
                    gItem.addTags(channel.getDefaultTags());
                }
            }
            if (item != null) {
                items.put(item.getName(), item);
                for (ProviderChangeListener<Item> listener : listeners) {
                    listener.added(this, item);
                }
            }
        }
    }

    private String getCategory(Channel channel) {
        if (channel.getChannelTypeUID() != null) {
            ChannelType channelType = TypeResolver.resolve(channel.getChannelTypeUID(), localeProvider.getLocale());
            if (channelType != null) {
                return channelType.getCategory();
            }
        }
        return null;
    }

    private String getLabel(Channel channel) {
        if (channel.getLabel() != null) {
            return channel.getLabel();
        } else {
            final Locale locale = localeProvider.getLocale();
            if (channel.getChannelTypeUID() != null) {
                final ChannelType channelType = TypeResolver.resolve(channel.getChannelTypeUID(), locale);
                if (channelType != null) {
                    return channelType.getLabel();
                }
            }
        }
        return null;
    }

    private void removeItem(String key) {
        if (!enabled) {
            return;
        }
        Item item = items.get(key);
        if (item != null) {
            for (ProviderChangeListener<Item> listener : listeners) {
                listener.removed(this, item);
            }
            items.remove(key);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    RegistryChangeListener<Thing> thingRegistryListener = new RegistryChangeListener<Thing>() {

        @Override
        public void added(Thing element) {
            for (Channel channel : element.getChannels()) {
                for (ItemChannelLink link : linkRegistry.getLinks(channel.getUID())) {
                    createItemForLink(link);
                }
            }
        }

        @Override
        public void removed(Thing element) {
            removeItem(element.getUID().toString());
        }

        @Override
        public void updated(Thing oldElement, Thing element) {
            removed(oldElement);
            added(element);
        }
    };

    RegistryChangeListener<ItemChannelLink> linkRegistryListener = new RegistryChangeListener<ItemChannelLink>() {

        @Override
        public void added(ItemChannelLink element) {
            createItemForLink(element);
            lastUpdate = System.nanoTime();
        }

        @Override
        public void removed(ItemChannelLink element) {
            removeItem(element.getItemName());
        }

        @Override
        public void updated(ItemChannelLink oldElement, ItemChannelLink element) {
            removed(oldElement);
            added(element);
        }
    };

    RegistryChangeListener<Item> itemRegistryListener = new RegistryChangeListener<Item>() {

        @Override
        public void added(Item element) {
            // check, if it is our own item
            for (Item item : items.values()) {
                if (item == element) {
                    return;
                }
            }
            // it is from some other provider, so remove ours, if we have one
            Item oldElement = items.get(element.getName());
            if (oldElement != null) {
                for (ProviderChangeListener<Item> listener : listeners) {
                    listener.removed(ChannelItemProvider.this, oldElement);
                }
                items.remove(element.getName());
            }
            lastUpdate = System.nanoTime();
        }

        @Override
        public void removed(Item element) {
            // check, if it is our own item
            for (Item item : items.values()) {
                if (item == element) {
                    return;
                }
            }
            // it is from some other provider, so create one ourselves if needed
            for (ChannelUID uid : linkRegistry.getBoundChannels(element.getName())) {
                for (ItemChannelLink link : linkRegistry.getLinks(uid)) {
                    if (itemRegistry.get(link.getItemName()) == null) {
                        createItemForLink(link);
                    }
                }
            }
        }

        @Override
        public void updated(Item oldElement, Item element) {
        }
    };
}
