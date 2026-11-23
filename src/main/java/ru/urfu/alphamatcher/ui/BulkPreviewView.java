package ru.urfu.alphamatcher.ui;

import java.time.LocalDate;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import ru.urfu.alphamatcher.demo.DemoDataService;
import ru.urfu.alphamatcher.demo.DemoOperationState.Query;
import ru.urfu.alphamatcher.demo.DemoSession;
import ru.urfu.alphamatcher.demo.DemoUser;
import ru.urfu.alphamatcher.matching.JdbcBulkPreview;
import ru.urfu.alphamatcher.matching.JdbcBulkExecution;
import ru.urfu.alphamatcher.matching.JdbcDemoDataService;

@Route(value="section/bulk-preview", layout=MainLayout.class)
@PageTitle("Долговременный предпросмотр - А.Выгодоприобретатель")
public class BulkPreviewView extends Div implements BeforeEnterObserver {
    record RegistryFilter(Query query, DemoUser user) { }
    private final Div content = new Div();
    private final TextField jobId = new TextField("Идентификатор задания");
    private final Div message = new Div();
    private final Div registryFilter = new Div();
    private final JdbcBulkPreview jobs;
    private long page;
    private boolean preparing;
    private final boolean executionAvailable;
    private volatile boolean executing;
    private final java.util.concurrent.atomic.AtomicBoolean executorBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<String> executionError = new java.util.concurrent.atomic.AtomicReference<>();

    public BulkPreviewView(DemoDataService data) {
        setClassName("app-page");
        jobs = data instanceof JdbcDemoDataService jdbc ? jdbc.bulkPreview() : null;
        executionAvailable = jobs!=null && VaadinSession.getCurrent()!=null && DemoSession.user()!=null
                && jobs.execution().available();
        if (VaadinSession.getCurrent() == null || DemoSession.user() == null) return;
        if (jobs == null || !jobs.available()) {
            add(UiParts.text("", "Долговременный предпросмотр доступен после подготовки изолированной MySQL."));
            return;
        }
        var from = new DatePicker("Начало периода", LocalDate.of(2026,10,1));
        var to = new DatePicker("Окончание периода", LocalDate.of(2026,10,12));
        var processed = new Checkbox("Включать обработанные");
        var recent = new com.vaadin.flow.component.select.Select<String>();
        jobId.setMaxLength(36);
        recent.setLabel("Последние задания");
        recent.setItems(jobs.recent(DemoSession.user()));
        recent.addValueChangeListener(event -> {
            if (event.getValue()!=null) run(() -> open(event.getValue()));
        });
        add(UiParts.text("registry-title", "Долговременный предпросмотр"),
                UiParts.text("muted", "Проверьте предложения и выбранные операции перед подтверждением."),
                registryFilter, UiParts.div("registry-filters", from,to,processed, action("Начать подготовку", () -> {
                    String id = jobs.start(DemoSession.user(),from.getValue(),to.getValue(),processed.getValue());
                    recent.setItems(jobs.recent(DemoSession.user()));
                    open(id);
                })),
                UiParts.div("registry-filters", recent,jobId,action("Открыть задание", () -> open(jobId.getValue()))),
                message,content);
        addAttachListener(event -> {
            var ui = event.getUI();
            var listener = ui.addPollListener(poll -> {
                String error=executionError.getAndSet(null);
                if (preparing || executing || error!=null) run(this::refresh);
                if (error!=null) message.setText(error);
            });
            var removeUserListener = DemoSession.onUserChanged(() -> {
                preparing=false;
                executing=false;
                ui.setPollInterval(-1);
                jobId.clear();
                recent.clear();
                registryFilter.removeAll();
                var user=DemoSession.user();
                recent.setItems(user==null ? java.util.List.of() : jobs.recent(user));
                message.setText("");
                content.removeAll();
            });
            var cleanup = new com.vaadin.flow.shared.Registration[1];
            cleanup[0] = addDetachListener(detach -> {
                listener.remove();
                executing=false;
                removeUserListener.run();
                ui.setPollInterval(-1);
                cleanup[0].remove();
            });
        });
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var incoming = ComponentUtil.getData(event.getUI(), RegistryFilter.class);
        ComponentUtil.setData(event.getUI(), RegistryFilter.class, null);
        if (incoming != null && java.util.Objects.equals(incoming.user(), DemoSession.user()))
            offerRegistryFilter(incoming.query());
    }

    private void offerRegistryFilter(Query query) {
        if (!executionAvailable) return;
        var criteria = new java.util.StringJoiner(" · ");
        if (!query.text().isBlank()) criteria.add(query.text());
        if (query.from() != null) criteria.add("с " + query.from().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")));
        if (query.to() != null) criteria.add("по " + query.to().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")));
        if (!query.status().isBlank()) criteria.add(query.status());
        if (!query.method().isBlank()) criteria.add(query.method());
        registryFilter.removeAll();
        registryFilter.add(UiParts.div("registry-filters",
                UiParts.text("muted", criteria.length() == 0 ? "Все операции реестра" : criteria.toString()),
                action("Подготовить по фильтру реестра", () -> open(jobs.startFiltered(DemoSession.user(), query)))));
    }

    private Button action(String caption,Runnable command) {
        return UiParts.button("app-action app-action--secondary",caption,() -> run(command));
    }

    private void run(Runnable command) {
        try {
            command.run();
            message.setText("");
        } catch (IllegalStateException | IllegalArgumentException failure) {
            message.setText(failure.getMessage());
            getUI().ifPresent(ui -> ui.setPollInterval(-1));
            preparing = false;
            content.removeAll();
        }
    }

    private void open(String id) {
        executing=false;
        jobId.setValue(id.strip());
        page=0;
        refresh();
    }

    private void refresh() {
        var user=DemoSession.user();
        String id=jobId.getValue().strip();
        var result=jobs.page(user,id,page);
        page=result.index();
        var job=result.job();
        preparing="PREPARING".equals(job.status());
        getUI().ifPresent(ui -> ui.setPollInterval(preparing || executing ? 2000 : -1));
        content.removeAll();
        content.add(UiParts.text("registry-title", switch (job.status()) {
            case "PREPARING" -> "Подготовка";
            case "READY" -> "Предпросмотр готов";
            case "CANCELLED" -> "Подготовка отменена";
            case "INTERRUPTED" -> "Подготовка прервана";
            default -> "Ошибка подготовки";
        }), UiParts.text("", "Проверено: " + job.inspected() + " · выбрано: " + job.selected()
                + " · SAFE: " + job.safe() + " · REVIEW: " + job.review()
                + " · нет договора: " + job.absent() + " · пропущено: " + job.skipped()));
        if (job.reason()!=null) content.add(UiParts.text("",job.reason()));
        if (preparing) {
            content.add(action("Отменить подготовку", () -> {
                jobs.cancel(DemoSession.user(),id);
                refresh();
            }),action("Закрыть прерванную подготовку", () -> {
                if (!jobs.abandonInterrupted(DemoSession.user(),id)) {
                    throw new IllegalStateException("Подготовка ещё активна. Откройте задание повторно.");
                }
                refresh();
            }));
            return;
        }
        if (!"READY".equals(job.status())) return;
        var progress=executionAvailable?jobs.execution().progress(user,id):null;
        long pages=Math.max(1,Math.ceilDiv(job.inspected(),10));
        if (progress==null) content.add(UiParts.div("matching-actions",
                action("Выбрать все результаты",() -> { jobs.selectAll(DemoSession.user(),id,true); refresh(); }),
                action("Сбросить выбор",() -> { jobs.selectAll(DemoSession.user(),id,false); refresh(); }),
                action("Обновить",this::refresh)));
        if (executionAvailable && progress==null && job.selected()>0)
            content.add(action("Подтвердить выбранное", () -> confirm(id,job)));
        if (progress!=null) renderExecution(id,progress);
        for (var row : result.rows()) {
            var selected = new Checkbox(row.reference() + " · " + row.status(),row.selected());
            selected.setEnabled(progress==null);
            selected.addValueChangeListener(event -> {
                if (event.isFromClient()) run(() -> {
                    jobs.select(DemoSession.user(),id,row.id(),event.getValue());
                    refresh();
                });
            });
            var text = UiParts.text("",row.evidence());
            text.getStyle().set("overflow-wrap","anywhere");
            content.add(UiParts.div("detail-block", selected,new Details("Основания решения",text)));
        }
        var previous=action("Предыдущая страница",() -> { page--; refresh(); });
        var next=action("Следующая страница",() -> { page++; refresh(); });
        previous.setEnabled(page>0);
        next.setEnabled(page+1<pages);
        var number=new TextField();
        number.setAriaLabel("Номер страницы");
        number.setClassName("app-pagination-input");
        number.setValue(Long.toString(page+1));
        number.addKeyPressListener(Key.ENTER, event -> {
            try {
                long requested=Long.parseLong(number.getValue().strip());
                if (requested<1 || requested>pages) throw new NumberFormatException();
                page=requested-1;
                run(this::refresh);
            } catch (NumberFormatException error) {
                number.setInvalid(true);
                number.setErrorMessage("Введите номер от 1 до "+pages);
            }
        });
        content.add(UiParts.div("registry-pagination",previous,UiParts.text("", "Страница"),number,
                UiParts.text("", "из "+pages),next));
    }

    private void confirm(String id,JdbcBulkPreview.Job job) {
        long employee=DemoSession.employeeGeneration();
        var dialog=new Dialog();
        dialog.setHeaderTitle("Подтвердить пакетное сопоставление");
        dialog.add(UiParts.text("", "Выбрано операций: "+job.selected()+". Применяются только заново проверенные SAFE."));
        dialog.getFooter().add(action("Отмена",dialog::close),action("Подтвердить", () -> {
            DemoSession.requireSameEmployeeGeneration(employee);
            if (!id.equals(jobId.getValue())) throw new IllegalStateException("Открыто другое задание.");
            dialog.close();
            var progress=jobs.execution().authorize(DemoSession.user(),id,job.selectionToken());
            if ("RUNNING".equals(progress.status())) execute(id); else refresh();
        }));
        dialog.open();
    }

    private void renderExecution(String id,JdbcBulkExecution.Progress progress) {
        content.add(UiParts.text("",switch (progress.status()) {
            case "RUNNING" -> "Выполнение";
            case "COMPLETED" -> "Исполнение завершено";
            case "CANCELLED" -> "Исполнение отменено";
            case "PARTIALLY_COMPLETED" -> "Исполнение завершено частично";
            default -> "Требуется новый предпросмотр";
        }),UiParts.text("", "Обработано: "+progress.processed()+" · привязано: "+progress.applied()
                +" · пакетов: "+progress.packages()));
        if (progress.reason()!=null) content.add(UiParts.text("",progress.reason()));
        if ("RUNNING".equals(progress.status())) {
            if (!executing) content.add(action("Продолжить выполнение",() -> execute(id)));
            content.add(action("Остановить выполнение",() -> {
                executing=false;
                jobs.execution().cancel(DemoSession.user(),id);
                refresh();
            }));
        }
        var journal=jobs.execution().journal(DemoSession.user(),id,page*10);
        if (!journal.isEmpty()) {
            var entries=new Div();
            for (var row:journal) entries.add(UiParts.text("",row.reference()+" · "+row.outcome()
                    +" · пакет "+row.packageNumber()+" · "+row.actor()+" · "+row.time()
                    +(row.contract()==null?"":" · DB-"+row.contract())));
            content.add(new Details("Журнал исполнения этой страницы",entries));
        }
    }

    private void execute(String id) {
        if (!executorBusy.compareAndSet(false,true)) return;
        var user=DemoSession.user();
        executing=true;
        getUI().ifPresent(ui -> ui.setPollInterval(2000));
        Thread.ofVirtual().name("bulk-execution-"+id).start(() -> {
            try {
                jobs.execution().execute(user,id,() -> executing);
            } catch (RuntimeException error) {
                executionError.set("Подключение или запись прерваны. Обновите задание и продолжите с сохранённого пакета.");
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Ошибка задания "+id,error);
            } finally {
                // Leave polling active for one refresh after the last package.
                executing=false;
                executorBusy.set(false);
                executionError.compareAndSet(null,"");
            }
        });
        refresh();
    }
}
