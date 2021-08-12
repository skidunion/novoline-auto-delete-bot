package com.novoline.autodelete;

import com.mewna.catnip.Catnip;
import com.mewna.catnip.CatnipOptions;
import com.mewna.catnip.entity.channel.Channel;
import com.mewna.catnip.entity.channel.GuildChannel;
import com.mewna.catnip.entity.guild.PermissionOverride;
import com.mewna.catnip.entity.message.Message;
import com.mewna.catnip.rest.guild.ChannelData;
import com.mewna.catnip.shard.DiscordEvent;
import org.tinylog.Logger;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.mewna.catnip.shard.GatewayIntent.GUILDS;
import static com.mewna.catnip.shard.GatewayIntent.GUILD_MESSAGES;
import static com.novoline.autodelete.Configuration.PREFIX;

public class AutoDeleteBot {

    private final Catnip catnip;

    private final ScheduledExecutorService scheduler;
    private final Map<String, Future<?>> channelFutureMap;

    /* package */ AutoDeleteBot(String token) {
        Logger.info("Starting AutoDeleteBot (token = {})", token);

        catnip = configureCatnip(token);
        catnip.connect();

        scheduler = Executors.newScheduledThreadPool(2);
        channelFutureMap = new HashMap<>();
    }

    private Catnip configureCatnip(String token) {
        CatnipOptions options = new CatnipOptions(token)
                .intents(Set.of(GUILDS, GUILD_MESSAGES));

        Catnip catnip = Catnip.catnip(options);

        catnip
                .observable(DiscordEvent.READY)
                .subscribe((e) -> Logger.debug("Connected to Discord ({})", e.user().discordTag()));

        catnip
                .observable(DiscordEvent.MESSAGE_CREATE)
                .filter(message -> Configuration.ALLOWED_USERS.contains(message.author().id()))
                .filter(message -> message.content().startsWith(PREFIX)
                        && !message.guild().isEmpty().blockingGet())
                .map(this::processCommand)
                .subscribe(this::handleCommand,
                           error -> Logger.error(error, "Uncaught exception"));

        return catnip;
    }

    private CommandContext processCommand(Message message) {
        String content = message.content().substring(PREFIX.length());
        String[] parts = content.split(" ");

        String commandName = parts[0];
        String[] args = content.substring(commandName.length()).trim().split(" ");

        return new CommandContext(message, commandName, args);
    }

    private void handleCommand(CommandContext ctx) {
        switch(ctx.commandName()) {
            case "set" -> {
                String duration = ctx.args()[0];
                String channelId = ctx.message().channelId();

                if(duration.equals("0")) {
                    var future = channelFutureMap.get(channelId);

                    if(future == null) {
                        ctx.message().respond("This channel is not set up for purging yet");
                    } else {
                        future.cancel(false);
                        ctx.message().respond("Ok, this channel will no longer be purged");
                    }
                } else {
                    long durationMillis = parseDuration(duration).toMillis();

                    // Cancel future for this channel if the user tried to re-set purge time
                    if(channelFutureMap.containsKey(channelId)) {
                        channelFutureMap.get(channelId).cancel(false);
                    }

                    ctx.message().channel()
                       .map(Channel::asGuildChannel)
                       .subscribe(channel -> {
                           var future = scheduler.scheduleAtFixedRate(
                                   new AutoDeleteTask(catnip, channel),
                                   durationMillis, durationMillis, TimeUnit.MILLISECONDS);

                           channelFutureMap.put(channelId, future);
                           ctx.message().respond("Ok, purging this channel every " + duration);
                       });
                }
            }
            case "purge" ->
                ctx.message().channel()
                   .map(Channel::asGuildChannel)
                   .subscribe(channel -> {
                       ChannelData currentData = ChannelData.of(channel);
                       channel.guild().blockingGet()
                              .createChannel(currentData)
                              .subscribe(newChannel -> {
                                  cloneOverrides(channel, newChannel);
                                  channel.delete();
                              });
                   });
            case "help" -> ctx.message().respond(
                """
                🗑️ auto-delete bot
                ```
                a!set <duration> - enables purging for the current channel
                a!set 0 - disables purging for the current channel
                a!purge - purge this channel now
                a!help - prints this message
                ```
                """
            );
            default -> ctx.message().respond("Unknown command, type `a!help` for help");
        }
    }

    private static void cloneOverrides(GuildChannel from, GuildChannel to) {
        for (PermissionOverride override : from.overrides()) {
            from.catnip().rest().channel()
                .editPermissionOverride(
                    to.id(), override.id(),
                    override.allow(), override.deny(),
                    override.type() == PermissionOverride.OverrideType.MEMBER)
                .blockingAwait();
        }
    }
    
    private static Duration parseDuration(String durationStr) {
        durationStr = durationStr.toLowerCase(Locale.ROOT);
        String designator = durationStr.endsWith("d") ? "P" : "PT";
        return Duration.parse(designator + durationStr);
    }

    private record CommandContext(
        Message message,
        String commandName,
        String[] args
    ) {}

    private final class AutoDeleteTask implements Runnable {

        private final Catnip catnip;
        private GuildChannel channel;

        public AutoDeleteTask(Catnip catnip, GuildChannel channel) {
            this.catnip = catnip;
            this.channel = channel;
        }

        @Override
        public void run() {
            String channelId = channel.id();
            ChannelData currentData = ChannelData.of(channel);

            var map = Collections.synchronizedMap(channelFutureMap);
            var thisFuture = map.get(channel.id());

            channel.guild().blockingGet()
                   .createChannel(currentData)
                   .subscribe(newChannel -> {
                       // Catnip's caching is weird and 'newChannel' lacks the 'allow' or 'deny' properties
                       // in newly created channels, thus we have to retrieve the correct overrides from the previous channel.
                       GuildChannel channelFromRest = catnip.rest().channel()
                               .getChannelById(channelId).blockingGet().asGuildChannel();

                       cloneOverrides(channelFromRest, newChannel);

                       channel.delete("Auto-purged");
                       channel = newChannel;

                       synchronized (map) {
                           map.remove(channelId);
                           map.put(newChannel.id(), thisFuture);
                       }
                   });
        }
    }
}
