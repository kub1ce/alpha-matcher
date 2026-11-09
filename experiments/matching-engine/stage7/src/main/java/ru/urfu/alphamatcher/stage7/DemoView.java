package ru.urfu.alphamatcher.stage7;

import java.util.*;
import java.io.*;
import com.vaadin.flow.component.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.component.page.ColorScheme;
import ru.urfu.alphamatcher.experiment.MatchingModel.*;
import tools.jackson.databind.json.JsonMapper;

@Route("")
@PageTitle("А.Выгодоприобретатель - Matching Engine")
public final class DemoView extends Div {
    private final DemoRuns runs;
    private final Div dashboard=new Div(),summary=new Div(),state=new Div(),notice=new Div();
    private final Button prepare,run,reset,compare;
    private final ComboBox<String> example=new ComboBox<>("Готовый набор"),status=new ComboBox<>("Статус");
    private final ComboBox<DemoRuns.Row> left=new ComboBox<>("Первый платёж"),right=new ComboBox<>("Второй платёж");
    private final TextField search=new TextField("Поиск");
    private final Grid<DemoRuns.Row> grid=new Grid<>();
    private final AppPagination pagination=new AppPagination(page->{this.page=page;filter();});
    private final Set<Dialog> openDialogs=new HashSet<>();
    private int page;
    private long revision=-1;
    private String displayedRun="";
    private final ProgressBar progress=new ProgressBar();
    private final Span timing=new Span();
    private final ComboBox<String> sort=new ComboBox<>("Порядок");
    private static final LinkedHashMap<String,String> PRESETS=new LinkedHashMap<>();
    static {PRESETS.put("Базовый - 7 платежей","BASE");PRESETS.put("Без технического отказа - 6 платежей","BASE6");for(var spec:PresetCatalog.specs())PRESETS.put(PresetCatalog.label(spec.id()),spec.id());}
    private final Upload upload;
    private static final List<String> GATES=List.of("OPERATION_ELIGIBLE","CONTRACT_ANALYSIS_COMPLETE","INPUT_VALID","OPERATION_PARTIES","NO_THIRD_PARTY","SEARCH_KEYS","CANDIDATE_SEARCH_COMPLETE","CANDIDATE_ROWS_CONSISTENT","CANDIDATES_FOUND","ABSENCE_EVIDENCE_COMPLETE","EXPLICIT_UNIQUE_CONTRACT","CONTRACT_NUMBER","PARTY_PAYER","PARTY_RECIPIENT","PURPOSE_INN","PURPOSE_NAMES","PURPOSE_ORGANIZATIONS","ROLE_CLAIMS","BENEFICIARY");
    public DemoView(DemoRuns runs,EngineIdentity identity){
        this.runs=runs;setClassName("stage7");
        Button theme=button("Сменить тему",false,()->getUI().ifPresent(ui->ui.getPage().executeJs("""
                const dark=document.documentElement.getAttribute('theme')!=='dark';
                document.documentElement.setAttribute('theme',dark?'dark':'light');
                document.documentElement.style.colorScheme=dark?'dark':'light';
                try{localStorage.setItem('stage7-theme',dark?'dark':'light');}catch(e){}
                """)));
        Div brand=new Div(new Span("α"),new Span("А.Выгодоприобретатель"));brand.setClassName("brand");
        theme.getStyle().set("margin-left","auto");
        Div header=new Div(brand,theme);header.setClassName("demo-header");
        add(header);
        H1 title=new H1("Сопоставление платежей");
        Span subtitle=new Span("Результаты предварительного сопоставления. Привязки не изменяются.");
        subtitle.setClassName("muted");
        Div heading=new Div(title,subtitle);heading.setClassName("page-heading");
        state.setId("run-state");state.getElement().setAttribute("role","status");state.getElement().setAttribute("aria-live","polite");
        notice.setClassName("error");notice.getElement().setAttribute("role","alert");notice.setId("input-error");
        example.setItems(PRESETS.keySet());example.setValue("Базовый - 7 платежей");example.setAllowCustomValue(false);example.setWidth("340px");
        prepare=button("Подготовить набор",false,()->safe(()->runs.preparePreset(PRESETS.get(example.getValue()))));
        run=button("Запустить сопоставление",true,()->safe(()->runs.run()));run.setId("run-matching");
        reset=button("Сбросить",false,()->safe(runs::reset));
        final BoundedBytes bytes=new BoundedBytes();
        upload=new Upload((name,mime)->{bytes.reset();return bytes;});
        upload.setMaxFiles(1);upload.setMaxFileSize(DataImport.MAX_BYTES);upload.setAcceptedFileTypes(".json","application/json");
        upload.setUploadButton(button("Загрузить JSON",false,()->{}));
        upload.setDropAllowed(false);
        upload.addSucceededListener(e->{safe(()->runs.prepareImported(DataImport.parse(bytes.toByteArray())));upload.clearFileList();});
        upload.addFileRejectedListener(e->notice.setText("Файл не принят: нужен JSON до 4 МБ."));
        upload.addFailedListener(e->notice.setText("Не удалось загрузить файл. Допустимый размер - 4 МБ."));
        Anchor download=new Anchor("example.json","Скачать пример JSON");download.getElement().setAttribute("download",true);
        Div prepareControls=new Div(example,prepare,upload,download);prepareControls.setClassName("prepare-controls");
        Div actions=new Div(run,reset);actions.setClassName("prepare-actions");
        progress.setId("matching-progress");progress.setVisible(false);progress.setWidthFull();
        Div preparation=new Div(new H2("Подготовка данных"),prepareControls,summary,notice,actions,state,progress,timing);
        preparation.setClassName("card preparation");summary.setClassName("muted dataset-summary");dashboard.setClassName("dashboard");dashboard.setId("dashboard");
        status.setItems("Все","SAFE","REVIEW","NO_CONTRACT","TECHNICAL_ERROR","SKIPPED");status.setValue("Все");status.setClearButtonVisible(false);
        search.setPlaceholder("ID, назначение или причина");search.setClearButtonVisible(true);search.setValueChangeMode(ValueChangeMode.EAGER);
        search.addValueChangeListener(e->{page=0;filter();});status.addValueChangeListener(e->{page=0;filter();});
        grid.addColumn(DemoRuns.Row::id).setHeader("ID платежа").setWidth("120px").setFlexGrow(0);
        grid.addComponentColumn(x->clipped(x.purpose())).setHeader("Назначение платежа").setWidth("390px").setFlexGrow(1);
        grid.addComponentColumn(x->badge(x.status())).setHeader("Статус").setWidth("180px").setFlexGrow(0);
        grid.addColumn(DemoRuns.Row::contract).setHeader("Предложенный договор").setWidth("200px").setFlexGrow(0);
        grid.addColumn(x->x.candidateCount()<0?"Не получено":Integer.toString(x.candidateCount())).setHeader("Кандидаты").setWidth("110px").setFlexGrow(0);
        grid.addComponentColumn(x->clipped(x.reason())).setHeader("Причина").setWidth("340px");
        grid.addComponentColumn(x->button("Подробнее",false,()->detail(x))).setHeader("").setWidth("140px").setFlexGrow(0);
        grid.setAllRowsVisible(true);grid.setId("results-grid");grid.setEmptyStateText("Подготовьте данные и запустите сопоставление.");
        sort.setItems("ID ↑","ID ↓","Статус","Кандидаты ↓");sort.setValue("ID ↑");sort.addValueChangeListener(e->{page=0;filter();});
        Div filters=new Div(search,status,sort);filters.setClassName("filters");
        Div results=new Div(new H2("Результаты"),filters,grid,pagination);results.setClassName("card results");
        left.setItems(q->runs.query(q.getFilter().orElse(""),"Все","ID ↑").stream().skip(q.getOffset()).limit(q.getLimit()));
        right.setItems(q->runs.query(q.getFilter().orElse(""),"Все","ID ↑").stream().skip(q.getOffset()).limit(q.getLimit()));
        left.setItemLabelGenerator(x->x.id()+" · "+x.status());right.setItemLabelGenerator(x->x.id()+" · "+x.status());
        left.addValueChangeListener(e->comparisonEnabled());right.addValueChangeListener(e->comparisonEnabled());
        compare=button("Сравнить платежи",false,()->{
            if(left.getValue()==null||right.getValue()==null||left.getValue()==right.getValue())return;
            Dialog d=new Dialog();d.setHeaderTitle("Сравнение платежей");d.setWidth("1400px");
            Div columns=new Div(details(runs.detail(left.getValue()),true),details(runs.detail(right.getValue()),true));columns.setClassName("comparison");
            d.add(columns);d.getFooter().add(button("Закрыть",false,d::close));openDialog(d);
        });
        Div comparison=new Div(new H2("Сравнение решений"),new Div(left,right,compare));comparison.setClassName("card compare-controls");
        Div content=new Div(heading,preparation,dashboard,results,comparison);content.setClassName("page");
        add(content);
        addAttachListener(e->{
            var ui=e.getUI();
            ui.getPage().executeJs("try{const t=localStorage.getItem('stage7-theme');if(t){document.documentElement.setAttribute('theme',t);document.documentElement.style.colorScheme=t;}}catch(e){}");
            ui.setPollInterval(500);ui.addPollListener(event->refresh());refresh();
        });
    }
    private void safe(Runnable action){
        try{notice.setText("");action.run();refresh();}
        catch(IllegalArgumentException|IllegalStateException e){notice.setText(e.getMessage());}
    }
    private void refresh(){
        var s=runs.snapshot();prepare.setEnabled(!s.busy());upload.setEnabled(!s.busy());example.setEnabled(!s.busy());reset.setEnabled(!s.busy());
        run.setEnabled(!s.busy()&&s.data()!=null);progress.setVisible(s.state()==DemoRuns.State.RUNNING);
        progress.setValue(s.total()==0?0:(double)s.processed()/s.total());
        timing.setText(s.state()==DemoRuns.State.RUNNING||s.state()==DemoRuns.State.COMPLETE?String.format(Locale.ROOT,"Обработано %d из %d · %.1f с",s.processed(),s.total(),s.currentElapsedNanos()/1e9):"");
        if(revision==s.revision())return;revision=s.revision();state.setText(s.message());state.getElement().setAttribute("data-state",s.state().name());
        summary.setText(s.data()==null?"Набор не загружен.":s.data().name()+": клиентов "+s.data().clients().size()+", договоров "+s.data().contracts().size()+", платежей "+s.data().payments().size()+".");
        dashboard.removeAll();var m=s.metrics();
        if(m.total()>0){dashboard.add(metric("Обработано",m.total()),metric("SAFE",m.safe()),metric("REVIEW",m.review()),metric("NO_CONTRACT",m.noContract()),metric("Технические ошибки",m.errors()),metric("Доля SAFE от обработанных",String.format(Locale.ROOT,"%.1f%%",m.safePercent())));if(m.skipped()>0)dashboard.add(metric("Пропущено",m.skipped()));}
        if(!Objects.equals(displayedRun,s.runId())){displayedRun=s.runId();for(var d:List.copyOf(openDialogs))d.close();left.clear();right.clear();page=0;}
        if(s.state()!=DemoRuns.State.RUNNING){filter();left.getDataProvider().refreshAll();right.getDataProvider().refreshAll();}
        comparisonEnabled();
    }
    private void comparisonEnabled(){if(compare!=null)compare.setEnabled(runs.snapshot().state()==DemoRuns.State.COMPLETE&&left.getValue()!=null&&right.getValue()!=null&&left.getValue().id()!=right.getValue().id());}
    private void filter(){
        var rows=runs.query(search.getValue(),status.getValue(),sort.getValue());
        var w=AppPagination.window(rows.size(),page);page=w.page();pagination.update(rows.size(),page);
        grid.setItems(rows.subList(rows.isEmpty()?0:w.start()-1,w.end()));
        grid.setEmptyStateText(runs.snapshot().state()==DemoRuns.State.COMPLETE?"Платежи по заданным условиям не найдены.":"Подготовьте данные и запустите сопоставление.");
    }
    private String contract(DemoRuns.Result x){
        if(x.decision()==null||x.decision().proposedContractId()==null)return "Не предложен";
        return x.candidates().stream().filter(c->c.id()==x.decision().proposedContractId()).map(c->c.number()+" · "+c.id()).findFirst().orElse(String.valueOf(x.decision().proposedContractId()));
    }
    private void openDialog(Dialog d){openDialogs.add(d);d.addOpenedChangeListener(e->{if(!e.isOpened())openDialogs.remove(d);});d.open();}
    private void detail(DemoRuns.Row row){var x=runs.detail(row);Dialog d=new Dialog();d.setHeaderTitle("Платёж "+x.id());d.setWidth("1100px");d.add(details(x,false));d.getFooter().add(button("Закрыть",false,d::close));openDialog(d);}
    private Div details(DemoRuns.Result x,boolean showTitle){
        Div body=new Div();body.setClassName("payment-detail");
        if(showTitle)body.add(new H2("Платёж "+x.id()));
        body.add(badge(x.status()),new H3("Исходное назначение"),textBlock(x.purpose()));
        if(x.operation()!=null){body.add(new H3("Участники"));x.operation().parties().forEach((role,p)->body.add(textBlock(party(role,p))));}
        if(x.injectedFault())body.add(textBlock("Этот пример намеренно вызывает SQLException при чтении кандидатов. Решение не подменяется."));
        if(x.decision()==null){body.add(new H3("Техническая ошибка"),textBlock(x.error()),textBlock("Decision и признаки не получены. Все SAFE gates: не выполнялись или результат не получен."));return body;}
        var d=x.decision();var f=d.features();
        body.add(new H3("Предложение"),textBlock(contract(x)),new H3("Извлечённые признаки"),
                textBlock("Номера договоров: "+values(f.contractNumbers())+"\nИНН: "+values(f.inns())+"\nНевалидные ИНН: "+values(f.invalidInns())+"\nФИО: "+values(f.names())+"\nОрганизации: "+values(f.organizations())+"\nПризнак третьего лица: "+(f.thirdParty()?"Да":"Нет")),
                new H3("Найденные договоры"));
        if(x.candidates().isEmpty())body.add(textBlock("Кандидаты отсутствуют. Полнота поиска указана отдельно ниже."));
        else {
            Div cards=new Div();int[] candidatePage={0};AppPagination[] pager=new AppPagination[1];Runnable[] render=new Runnable[1];
            render[0]=()->{cards.removeAll();var w=AppPagination.window(x.candidates().size(),candidatePage[0]);candidatePage[0]=w.page();
                for(var c:x.candidates().subList(w.start()-1,w.end())){Div card=new Div(new H3(c.number()+" · ID "+c.id()));card.setClassName("candidate");c.parties().forEach((role,p)->card.add(textBlock(party(role,p))));cards.add(card);}pager[0].update(x.candidates().size(),candidatePage[0]);};
            pager[0]=new AppPagination(n->{candidatePage[0]=n;render[0].run();});render[0].run();body.add(cards,pager[0]);
        }
        body.add(new H3("SAFE gates"));
        for(String code:GATES){
            var check=d.checks().stream().filter(c->c.code().equals(code)).findFirst();
            Div row=new Div(badge(check.isEmpty()?"Не выполнялась":check.get().passed()?"PASS":"FAIL"),
                    new Span(gateName(code)),textBlock(check.map(Check::explanation).orElse("Эта ветка проверки не выполнялась.")));
            row.setClassName("gate");body.add(row);
        }
        body.add(new H3("Почему принято решение"),textBlock(x.reason()));
        var e=d.searchEvidence();
        body.add(new H3("Доказательства поиска"),textBlock("Полнота поиска: "+(e.completeness()==ru.urfu.alphamatcher.experiment.PurposeParser.Completeness.COMPLETE?"Полный":"Неполный")+"\nРезультат SQL прочитан полностью: "+(e.queryFullyConsumed()?"Да":"Нет")));
        Details raw=new Details();raw.setSummaryText("Полные технические сведения");boolean[] loaded={false};
        raw.addOpenedChangeListener(event->{if(event.isOpened()&&!loaded[0]){loaded[0]=true;try{raw.add(textBlock(new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(x)));}catch(RuntimeException failure){raw.add(textBlock("Не удалось отобразить технические сведения."));}}});body.add(raw);
        return body;
    }
    private static String values(Collection<?> items){return items==null||items.isEmpty()?"Не обнаружено":String.join(", ",items.stream().map(String::valueOf).toList());}
    private static String gateName(String code){return switch(code){
        case "OPERATION_ELIGIBLE"->"Доступность операции";case "CONTRACT_ANALYSIS_COMPLETE"->"Полнота анализа номера";case "INPUT_VALID"->"Корректность данных";case "OPERATION_PARTIES"->"Участники операции";case "NO_THIRD_PARTY"->"Проверка третьих лиц";case "SEARCH_KEYS"->"Реквизиты поиска";case "CANDIDATE_SEARCH_COMPLETE"->"Полнота поиска кандидатов";case "CANDIDATE_ROWS_CONSISTENT"->"Согласованность данных кандидатов";case "CANDIDATES_FOUND"->"Наличие договоров";case "ABSENCE_EVIDENCE_COMPLETE"->"Подтверждение отсутствия договора";case "EXPLICIT_UNIQUE_CONTRACT"->"Однозначность договора";case "CONTRACT_NUMBER"->"Номер договора";case "PARTY_PAYER"->"Плательщик";case "PARTY_RECIPIENT"->"Получатель";case "PURPOSE_INN"->"ИНН в назначении";case "PURPOSE_NAMES"->"ФИО в назначении";case "PURPOSE_ORGANIZATIONS"->"Организации в назначении";case "ROLE_CLAIMS"->"Роли участников";case "BENEFICIARY"->"Выгодоприобретатель";default->code;};}
    private static String party(String role,Party p){
        String label=switch(role){case "PAYER"->"Плательщик";case "RECIPIENT"->"Получатель";case "BENEFICIARY"->"Выгодоприобретатель";default->role;};
        String name=p.lastName()!=null?String.join(" ",Objects.toString(p.lastName(),""),Objects.toString(p.firstName(),""),Objects.toString(p.middleName(),"")):Objects.toString(p.clientName(),Objects.toString(p.name(),"Не указан"));
        return label+": "+name.strip()+"\nID клиента: "+Objects.toString(p.clientId(),"Не указан")+"; ИНН стороны: "+Objects.toString(p.inn(),"Не указан")+"; ИНН клиента: "+Objects.toString(p.clientInn(),"Не указан")+"; роль активна: "+(p.activeRole()?"Да":"Нет");
    }
    private static Div textBlock(String text){Div d=new Div();d.setText(text);d.setClassName("text-block");return d;}
    private static Span clipped(String text){Span s=new Span(text);s.setClassName("clipped");s.getElement().setAttribute("title",text);return s;}
    private static Span badge(String text){Span s=new Span(text);s.setClassName("badge badge-"+text.replace(' ','-'));return s;}
    private static Div metric(String label,Object value){Span v=new Span(String.valueOf(value));v.setClassName("metric-value");Div d=new Div(new Span(label),v);d.setClassName("metric");return d;}
    private static Button button(String label,boolean primary,Runnable action){Button b=new Button(label,e->action.run());b.setClassName("app-action "+(primary?"app-action--primary":"app-action--secondary"));return b;}
    private static final class BoundedBytes extends ByteArrayOutputStream {
        private void check(int n){if(count+n>DataImport.MAX_BYTES)throw new IllegalArgumentException("Файл превышает 4 МБ.");}
        @Override public synchronized void write(int b){check(1);super.write(b);}
        @Override public synchronized void write(byte[] b,int off,int len){check(len);super.write(b,off,len);}
    }
}
