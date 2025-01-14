package net.creeperhost.ftbbackups;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.brigadier.CommandDispatcher;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.platform.Platform;
import net.creeperhost.ftbbackups.commands.BackupCommand;
import net.creeperhost.ftbbackups.config.Config;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class FTBBackups {
    public static final String MOD_ID = "ftbbackups2";
    public static Logger LOGGER = LogManager.getLogger();
    public static Path configFile = Platform.getConfigFolder().resolve(MOD_ID + ".json");

    public static ScheduledExecutorService configWatcherExecutorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat("FTB Backups Config Watcher %d").setDaemon(true).build());
    public static ScheduledExecutorService backupCleanerWatcherExecutorService = Executors.newScheduledThreadPool(1, new ThreadFactoryBuilder().setNameFormat("FTB Backups scheduled executor %d").setDaemon(true).build());
    public static ExecutorService backupExecutor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("FTB Backups backup thread %d").setDaemon(true).build());

    public static MinecraftServer minecraftServer;
    public static Scheduler scheduler;

    public static boolean isShutdown = false;

    public static void init() {
        Config.init(configFile.toFile());
        CommandRegistrationEvent.EVENT.register(FTBBackups::onCommandRegisterEvent);
        LifecycleEvent.SERVER_STARTED.register(FTBBackups::serverStartedEvent);
        LifecycleEvent.SERVER_STOPPED.register(FTBBackups::serverStoppedEvent);
        LifecycleEvent.SERVER_LEVEL_SAVE.register(FTBBackups::serverSaveEvent);
        Runtime.getRuntime().addShutdownHook(new Thread(FTBBackups::killOutThreads));
    }

    private static void serverSaveEvent(ServerLevel serverLevel) {
        if(serverLevel == null || serverLevel.isClientSide) return;
        ServerPlayer player = serverLevel.getRandomPlayer();
        if(player != null ) {
            BackupHandler.isDirty = true;
        }
    }

    private static void serverStartedEvent(MinecraftServer minecraftServer) {
        FTBBackups.minecraftServer = minecraftServer;
        BackupHandler.init(minecraftServer);
        isShutdown = false;
        if (Config.cached().enabled) {
            if (!CronExpression.isValidExpression(Config.cached().backup_cron)) {
                FTBBackups.LOGGER.error("backup_cron is invalid, restoring default value");
                Config.cached().backup_cron = "0 */30 * * * ?";
                Config.saveConfig();
            }
            try {
                JobDetail jobDetail = JobBuilder.newJob(BackupJob.class).withIdentity(MOD_ID).build();
                Properties properties = new Properties();
                properties.put("org.quartz.scheduler.instanceName", MOD_ID);
                properties.put("org.quartz.threadPool.threadCount", "1");
                SchedulerFactory schedulerFactory = new StdSchedulerFactory(properties);
                scheduler = schedulerFactory.getScheduler();
                CronTrigger trigger = TriggerBuilder.newTrigger()
                        .withIdentity(MOD_ID)
                        .withSchedule(CronScheduleBuilder.cronSchedule(Config.cached().backup_cron))
                        .build();

                scheduler.start();
                scheduler.scheduleJob(jobDetail, trigger);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void serverStoppedEvent(MinecraftServer minecraftServer) {
        killOutThreads();
    }

    public static void killOutThreads()
    {
        try
        {
            int shutdownCount = 0;
            FTBBackups.isShutdown = true;

            while(BackupHandler.isRunning())
            {
                if(shutdownCount > 120) break;
                //Let's hold up shutting down if we're mid-backup I guess... But limit it to waiting 2 minutes.
                try {
                    if (shutdownCount % 10 == 0) FTBBackups.LOGGER.info("Backup in progress, Waiting for it to finish before shutting down.");
                    Thread.sleep(1000);
                    shutdownCount++;
                } catch (InterruptedException ignored) {}
            }

            scheduler.clear();
            scheduler.shutdown(false);
            Config.watcher.get().close();
            FTBBackups.configWatcherExecutorService.shutdownNow();
            FTBBackups.backupCleanerWatcherExecutorService.shutdownNow();
            FTBBackups.backupExecutor.shutdownNow();
            BackupHandler.backupRunning.set(false);

        } catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private static void onCommandRegisterEvent(CommandDispatcher<CommandSourceStack> cs, Commands.CommandSelection commandSelection) {
        cs.register(BackupCommand.register());
    }

    public static class BackupJob implements Job {
        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            if (FTBBackups.minecraftServer != null) {
                FTBBackups.LOGGER.info("Attempting to create an automatic backup");
                BackupHandler.createBackup(FTBBackups.minecraftServer);
            }
        }
    }
}
