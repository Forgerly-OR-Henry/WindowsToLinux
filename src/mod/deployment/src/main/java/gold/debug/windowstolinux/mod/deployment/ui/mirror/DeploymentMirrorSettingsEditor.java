package gold.debug.windowstolinux.mod.deployment.ui.mirror;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;
import gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint;
import gold.debug.windowstolinux.mod.deployment.service.mirror.MirrorSettings;

/** Edits retained rows without using the network or writing configuration resources. */
final class DeploymentMirrorSettingsEditor extends JPanel {
    private final MirrorCatalog catalog;
    private final ResourceBundle messages;
    private final BooleanSupplier consent;
    private final Map<DownloadPoint, Chain> chains = new EnumMap<>(DownloadPoint.class);
    private Map<String, String> regions = new LinkedHashMap<>();
    private List<SecretChoice> credentials = List.of();
    private boolean inheritedGnuMake;

    DeploymentMirrorSettingsEditor(MirrorCatalog catalog, ResourceBundle messages, BooleanSupplier consent) {
        this.catalog = Objects.requireNonNull(catalog);
        this.messages = Objects.requireNonNull(messages);
        this.consent = Objects.requireNonNull(consent);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        for (var point : DownloadPoint.values()) {
            var chain = new Chain(point);
            chains.put(point, chain);
            add(chain);
        }
    }

    void load(MirrorSettings settings) {
        regions = new LinkedHashMap<>(settings.serverRegions());
        inheritedGnuMake = !settings.points().containsKey(DownloadPoint.GNU_MAKE_SOURCE);
        chains.forEach((point, chain) -> chain.load(point == DownloadPoint.GNU_MAKE_SOURCE && inheritedGnuMake
            ? List.of(new MirrorSettings.Row(DownloadPoint.GNU_MAKE_PROVIDER, "", false, false, ""))
            : settings.rows(point)));
    }

    Map<String, String> regions() { return Map.copyOf(regions); }

    void credentials(List<String> references) {
        credentials = references.stream().map(value -> new SecretChoice(value, value)).toList();
        chains.values().forEach(c -> c.rows.forEach(Row::credentials));
    }

    void region(String server, String country) {
        if (country.isEmpty()) regions.remove(server); else regions.put(server, country);
    }

    MirrorSettings values() {
        var points = new EnumMap<DownloadPoint, List<MirrorSettings.Row>>(DownloadPoint.class);
        chains.forEach((point, chain) -> {
            var rows = chain.rows.stream().map(Row::value).toList();
            if (point != DownloadPoint.GNU_MAKE_SOURCE || !inheritedGnuMake || rows.getFirst().automatic())
                points.put(point, rows);
        });
        return new MirrorSettings(1, points, regions);
    }

    private record Choice(String id, String label) {
        @Override public String toString() { return label; }
    }
    private record SecretChoice(String reference, String label) {
        @Override public String toString() { return label; }
    }

    private final class Chain extends JPanel {
        final DownloadPoint point;
        final List<Row> rows = new ArrayList<>();
        final JPanel list = new JPanel();
        final JButton add = new JButton("＋");

        Chain(DownloadPoint point) {
            super(new BorderLayout(0, 4));
            this.point = point;
            setName("deployment.mirror.chain." + point.name());
            String key = "point." + point.name();
            setBorder(BorderFactory.createTitledBorder(messages.containsKey(key) ? messages.getString(key) : catalog.point(point).label()));
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
            add(list, BorderLayout.CENTER);
            if (catalog.providersFor(point).size() == 1) {
                var notice = new JLabel(messages.getString("source.single"));
                notice.setToolTipText(catalog.point(point).coverageNote());
                add(notice, BorderLayout.NORTH);
            }
            var footer = new JPanel(new FlowLayout(FlowLayout.LEADING));
            footer.add(add);
            add(footer, BorderLayout.SOUTH);
            add.setName("deployment.mirror.add." + point.name());
            add.addActionListener(event -> {
                if (rows.size() < 3 && rows.stream().noneMatch(r -> r.automatic.isSelected())) {
                    rows.add(new Row(this, null));
                    refresh();
                }
            });
        }

        void load(List<MirrorSettings.Row> saved) {
            rows.clear();
            saved.forEach(value -> rows.add(new Row(this, value)));
            refresh();
        }

        void refresh() {
            list.removeAll();
            boolean active = true;
            int index = 1;
            for (var row : rows) {
                row.priority.setText(messages.getString("priority") + " " + index++);
                row.remove.setVisible(index > 2);
                if (active) list.add(row);
                if (row.automatic.isSelected()) active = false;
            }
            add.setVisible(point != DownloadPoint.GNU_MAKE_SOURCE && active && rows.size() < 3);
            revalidate();
            repaint();
        }
    }

    private final class Row extends JPanel {
        final JLabel priority = new JLabel();
        final JComboBox<Choice> source = new JComboBox<>();
        final JTextField custom = new JTextField(28);
        final JCheckBox automatic = new JCheckBox(messages.getString("automatic"));
        final JButton remove = new JButton(messages.getString("remove"));
        final JComboBox<SecretChoice> credential = new JComboBox<>();
        final boolean fixedOfficial;
        boolean accepted;

        Row(Chain chain, MirrorSettings.Row saved) {
            super(new FlowLayout(FlowLayout.LEADING));
            fixedOfficial = chain.point == DownloadPoint.GNU_MAKE_SOURCE;
            if (!fixedOfficial) source.addItem(new Choice("", messages.getString("choose")));
            else automatic.setText(messages.getString("source.fixed.disabled"));
            for (var provider : catalog.providersFor(chain.point)) {
                String location = provider.country().matches("[A-Z]{2}")
                    ? new Locale.Builder().setRegion(provider.country()).build().getDisplayCountry(messages.getLocale())
                    : messages.getString("source.global");
                source.addItem(new Choice(provider.id(), provider.label() + " · " + location));
            }
            if (!fixedOfficial) source.addItem(new Choice("custom", messages.getString("custom")));
            credential.addItem(new SecretChoice("", messages.getString("credential.none")));
            if (saved == null && source.getItemCount() > 2) source.setSelectedIndex(1);
            if (saved != null) {
                String selected = saved.customUrl().isEmpty() ? saved.providerId() : "custom";
                boolean found = false;
                for (int i = 0; i < source.getItemCount(); i++) if (source.getItemAt(i).id.equals(selected)) {
                    source.setSelectedIndex(i); found = true; break;
                }
                if (!selected.isEmpty() && !found) {
                    source.addItem(new Choice(selected, messages.getString("unavailable") + " " + selected));
                    source.setSelectedIndex(source.getItemCount() - 1);
                }
                custom.setText(saved.customUrl());
                automatic.setSelected(saved.automatic() && (!fixedOfficial || !saved.privacyConsent()));
                accepted = !fixedOfficial && saved.privacyConsent();
                if (!saved.secretRef().isEmpty()) {
                    credential.addItem(new SecretChoice(saved.secretRef(), saved.secretRef()));
                    credential.setSelectedIndex(1);
                }
            }
            credentials();
            source.setName("deployment.mirror.source." + chain.point.name());
            automatic.setName("deployment.mirror.auto." + chain.point.name());
            credential.setToolTipText(messages.getString("credential.hint"));
            add(priority); add(source); add(custom); add(credential); add(automatic); add(remove);
            source.addActionListener(event -> refresh());
            automatic.addActionListener(event -> {
                if (!fixedOfficial && automatic.isSelected()) {
                    if (!consent.getAsBoolean()) automatic.setSelected(false);
                    else accepted = true;
                }
                refresh();
                chain.refresh();
            });
            remove.addActionListener(event -> {
                if (chain.rows.indexOf(this) > 0) { chain.rows.remove(this); chain.refresh(); }
            });
            refresh();
        }

        void refresh() {
            source.setEnabled(!automatic.isSelected());
            custom.setVisible(((Choice)source.getSelectedItem()).id.equals("custom"));
            custom.setEnabled(!automatic.isSelected());
            credential.setEnabled(!automatic.isSelected());
            credential.setVisible(!fixedOfficial);
        }

        void credentials() {
            if (fixedOfficial) return;
            String selected = credential.getSelectedItem() instanceof SecretChoice choice ? choice.reference() : "";
            credential.removeAllItems();
            credential.addItem(new SecretChoice("", messages.getString("credential.none")));
            DeploymentMirrorSettingsEditor.this.credentials.forEach(credential::addItem);
            if (!selected.isEmpty() && DeploymentMirrorSettingsEditor.this.credentials.stream().noneMatch(c -> c.reference().equals(selected)))
                credential.addItem(new SecretChoice(selected, selected));
            for (int i = 0; i < credential.getItemCount(); i++)
                if (credential.getItemAt(i).reference().equals(selected)) credential.setSelectedIndex(i);
        }

        MirrorSettings.Row value() {
            String selected = ((Choice)source.getSelectedItem()).id;
            return new MirrorSettings.Row(selected.equals("custom") ? "" : selected,
                selected.equals("custom") ? custom.getText().trim() : "", automatic.isSelected(), accepted,
                ((SecretChoice)credential.getSelectedItem()).reference());
        }
    }
}
