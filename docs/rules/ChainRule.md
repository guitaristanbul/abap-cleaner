[<-- previous rule](NeedlessSpacesRule.md) | [overview](../rules.md) | [next rule -->](LocalDeclarationOrderRule.md)

# Unchain into multiple statements

Resolves a chain \(DATA:, FIELD-SYMBOLS:, ASSERT: etc.\) into multiple standalone statements. The chain is kept, however, for declarations with BEGIN OF ... END OF blocks.

## References

* [Clean ABAP Styleguide: Do not chain up-front declarations](https://github.com/SAP/styleguides/blob/main/clean-abap/CleanABAP.md#do-not-chain-up-front-declarations)
* [Clean Code Checks: Chain Declaration Usage](https://github.com/SAP/code-pal-for-abap/blob/master/docs/checks/chain-declaration-usage.md)
* [ABAP Keyword Documentation: Only use chained statements where appropriate](https://help.sap.com/doc/abapdocu_latest_index_htm/latest/en-US/index.htm?file=abenchained_statements_guidl.htm)

## Options

* \[X\] Unchain declarations in CLASS ... DEFINITION sections
* \[X\] Unchain declarations in methods etc.
* \[ \] Unchain simple commands \(chain after first keyword, e.g. ASSERT:, CHECK:, CLEAR:, FREE:\) except WRITE:
* \[X\] Unchain complex commands \(a \+= : 1,2,3 etc.\)

## Examples


```ABAP

CLASS cl_unchaining DEFINITION.
  PUBLIC SECTION.
    CONSTANTS: any_constant TYPE i VALUE 1,
               other_constant TYPE i VALUE 2.

    " BEGIN OF ... END OF blocks are always kept as a chain:
    TYPES: 
      BEGIN of xy,
        a TYPE x,
        b TYPE y,
      END OF xy.

    METHODS:
      setup,
      unchain.
ENDCLASS.

CLASS cl_unchaining IMPLEMENTATION.
  METHOD unchain.
    CONSTANTS: 
      lc_list_price_1200 TYPE ty_list_price VALUE 1200,
      lc_amount_1000 TYPE ty_amount VALUE 1000,
      lc_num_contract_change TYPE ty_sequence_number VALUE 1.

    " alignment within the declaration line is not changed; empty lines are kept
    DATA: lth_any_hash_table TYPE ty_th_hash_table, " comment
          lo_contract TYPE REF TO cl_contract  ##NEEDED,

          ls_structure TYPE if_any_interface=>ty_s_structure,
          mv_bool_variable TYPE abap_bool VALUE abap_false ##NO_TEXT.

    FIELD-SYMBOLS: " comment above the first identifier
      <ls_data>      TYPE ty_s_data, ##PRAGMA_IN_WRONG_POSITION
      <ls_amount>  LIKE LINE OF its_amount, " comment

      " comment line within the chain
      <ls_contract_data> TYPE ty_s_contract_data,
      <ls_parameter>   LIKE LINE OF mt_parameter.

    ASSERT: lv_count = 1,
            lts_table IS NOT INITIAL.

    CLEAR: ev_result_a, ev_result_b.

    " WRITE: chains make sense, as the output is on one line, too; they are therefore kept
    WRITE: / `text`, iv_value, `more text`, iv_other_value.

    lv_value += 2 ** : 1, 2, 3.

    add_value( lv_value ):,,.

    CALL METHOD any_method
      EXPORTING iv_value_a = 'text'
                iv_value_b = : 42, 84.
  ENDMETHOD.
ENDCLASS.
```

Resulting code:

```ABAP

CLASS cl_unchaining DEFINITION.
  PUBLIC SECTION.
    CONSTANTS any_constant TYPE i VALUE 1.
    CONSTANTS other_constant TYPE i VALUE 2.

    " BEGIN OF ... END OF blocks are always kept as a chain:
    TYPES:
      BEGIN of xy,
        a TYPE x,
        b TYPE y,
      END OF xy.

    METHODS setup.
    METHODS unchain.
ENDCLASS.

CLASS cl_unchaining IMPLEMENTATION.
  METHOD unchain.
    CONSTANTS lc_list_price_1200 TYPE ty_list_price VALUE 1200.
    CONSTANTS lc_amount_1000 TYPE ty_amount VALUE 1000.
    CONSTANTS lc_num_contract_change TYPE ty_sequence_number VALUE 1.

    " alignment within the declaration line is not changed; empty lines are kept
    DATA lth_any_hash_table TYPE ty_th_hash_table. " comment
    DATA lo_contract TYPE REF TO cl_contract  ##NEEDED.

    DATA ls_structure TYPE if_any_interface=>ty_s_structure.
    DATA mv_bool_variable TYPE abap_bool VALUE abap_false ##NO_TEXT.

    " comment above the first identifier
    FIELD-SYMBOLS <ls_data>      TYPE ty_s_data. ##PRAGMA_IN_WRONG_POSITION
    FIELD-SYMBOLS <ls_amount>  LIKE LINE OF its_amount. " comment

    " comment line within the chain
    FIELD-SYMBOLS <ls_contract_data> TYPE ty_s_contract_data.
    FIELD-SYMBOLS <ls_parameter>   LIKE LINE OF mt_parameter.

    ASSERT: lv_count = 1,
            lts_table IS NOT INITIAL.

    CLEAR: ev_result_a, ev_result_b.

    " WRITE: chains make sense, as the output is on one line, too; they are therefore kept
    WRITE: / `text`, iv_value, `more text`, iv_other_value.

    lv_value += 2 ** 1.
    lv_value += 2 ** 2.
    lv_value += 2 ** 3.

    add_value( lv_value ).
    add_value( lv_value ).
    add_value( lv_value ).

    CALL METHOD any_method
      EXPORTING iv_value_a = 'text'
                iv_value_b = 42.
    CALL METHOD any_method
      EXPORTING iv_value_a = 'text'
                iv_value_b = 84.
  ENDMETHOD.
ENDCLASS.
```

## Related code

* [Rule implementation](../../com.sap.adt.abapcleaner/src/com/sap/adt/abapcleaner/rules/declarations/ChainRule.java)
* [Tests](../../test/com.sap.adt.abapcleaner.test/src/com/sap/adt/abapcleaner/rules/declarations/ChainTest.java)

