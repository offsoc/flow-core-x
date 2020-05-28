package com.flowci.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author yang
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class SimpleToken implements SimpleSecret {

    private String data;
}
